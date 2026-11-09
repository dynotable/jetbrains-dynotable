package com.dynotable.editor

import com.dynotable.connect.ConnectCore
import com.dynotable.connect.DynoTableServer
import com.dynotable.connect.SessionEnded
import com.dynotable.files.DynoTableFileType
import com.dynotable.files.OpenSearchFileType
import com.dynotable.protocol.CommandArgs
import com.dynotable.protocol.OPEN_IN_APP
import com.dynotable.protocol.RUN_STATEMENT
import com.dynotable.refusals.Failure
import com.dynotable.refusals.Refusal
import com.dynotable.refusals.RefusalAction
import com.dynotable.refusals.UNREADABLE
import com.dynotable.refusals.notify
import com.dynotable.refusals.refusalOf
import com.dynotable.results.BUSY
import com.dynotable.results.Results
import com.dynotable.results.RunOutcome
import com.dynotable.status.Action
import com.dynotable.status.Status
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.Lsp4jServer
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.customization.LspCommandsSupport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.lsp4j.CodeLens
import org.eclipse.lsp4j.CodeLensParams
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import java.util.concurrent.CompletableFuture

const val NOT_OPENED = "DynoTable has not opened this file. Close and open the file, then try again."
const val CHANGED = "The query changed before the run started. Run it again."
const val OPEN_CHANGED = "The query changed before DynoTable opened it. Try again."
const val NO_OPEN = "DynoTable cannot open this statement. Put the cursor in a statement that shows Open in DynoTable."
private const val CODE_LENS = "textDocument/codeLens"
private val gson = Gson()

/** The platform sends a command as a notification, so its answer and its refusal would be lost. */
object DynoTableCommands : LspCommandsSupport() {
  override fun executeCommand(lspClient: LspClient, contextFile: VirtualFile, command: Command) {
    if (command.command.startsWith("dynotable.")) {
      ServerCommands.getInstance(lspClient.project).launch(contextFile, command.command, command.arguments.orEmpty())
    } else {
      super.executeCommand(lspClient, contextFile, command)
    }
  }
}

/** What a request gave a caller that shows its failure itself. */
sealed interface Reply

/** A command's answer. */
class Answer(val value: Any?) : Reply

class Unanswered(val failure: Failure) : Reply

/** Sends the server's commands: connects first, and shows a refusal once, in the server's own sentence. */
@Service(Service.Level.PROJECT)
class ServerCommands(private val project: Project, private val cs: CoroutineScope) {
  private val core get() = ConnectCore.getInstance(project)

  /** For a caller on the EDT, which must not wait. */
  fun launch(file: VirtualFile, command: String, args: List<Any?>) {
    cs.launch { send(file, command, args) }
  }

  /** `null` when the command did not answer; then the user has seen why, or the status says it. */
  suspend fun send(file: VirtualFile, command: String, args: List<Any?>): Answer? {
    val client = ready(file) ?: return null
    if (command == RUN_STATEMENT && CommandArgs.orNull(args.firstOrNull()) != null) {
      runStatement(client, file, args.first())
      return null
    }
    return command(client, file, command, args)
  }

  /** A custom request of [client]'s session. `null` when it did not answer; then the user has seen why, or the status says it. */
  suspend fun <T> custom(client: LspClient, method: String, sender: (DynoTableServer) -> CompletableFuture<T>): Answer? =
    when (val reply = reply(client, sender)) {
      is Answer -> reply
      is Unanswered -> {
        report(null, reply.failure, method, emptyList())
        null
      }
    }

  /** A custom request of [client]'s session that shows nothing: the caller shows its failure. */
  suspend fun <T> reply(client: LspClient, sender: (DynoTableServer) -> CompletableFuture<T>): Reply =
    request(client, holdsRestart = false) { sender(it as DynoTableServer) }

  private suspend fun command(client: LspClient, file: VirtualFile, command: String, args: List<Any?>): Answer? =
    when (val reply = request(client, holdsRestart = false) { it.workspaceService.executeCommand(ExecuteCommandParams(command, args)) }) {
      is Answer -> reply
      is Unanswered -> {
        report(file, reply.failure, command, args)
        null
      }
    }

  /** For a caller on the EDT, which must not wait. */
  fun launchRun(editor: Editor) {
    cs.launch { run(editor) }
  }

  /** Run: the selection, else the server's statement at the caret, else the whole document. */
  suspend fun run(editor: Editor) {
    val (client, request) = ready(editor) ?: return
    val uri = client.getDocumentIdentifier(request.file).uri
    val args = JsonObject().apply { addProperty("uri", uri) }
    if (request.selection != null) {
      args.add("range", gson.toJsonTree(request.selection))
    } else if (request.file.fileType != OpenSearchFileType) {
      // Without a range the server runs the whole document; for opensearch that is the only form.
      val lens = lensAtCaret(client, request, uri, RUN_STATEMENT, CHANGED) ?: return
      lens.arg?.let { CommandArgs.from(it).range }?.let { args.add("range", gson.toJsonTree(it)) }
    }
    runStatement(client, request.file, args)
  }

  /** For a caller on the EDT, which must not wait. */
  fun launchOpen(editor: Editor) {
    cs.launch { open(editor) }
  }

  /** Open in DynoTable: the server decides which statements get an Open lens, and with which argument. */
  suspend fun open(editor: Editor) {
    val (client, request) = ready(editor) ?: return
    val uri = client.getDocumentIdentifier(request.file).uri
    val lens = lensAtCaret(client, request, uri, OPEN_IN_APP, OPEN_CHANGED) ?: return
    if (lens.arg == null) {
      notify(project, NO_OPEN, NotificationType.INFORMATION)
      return
    }
    command(client, request.file, OPEN_IN_APP, listOf(lens.arg))
  }

  /** [arg] is `null` when no [command] lens holds the caret. */
  private class Lens(val arg: Any?)

  /** The server's [command] lens at the caret; `null` once the user saw why nothing goes on. */
  private suspend fun lensAtCaret(client: LspClient, request: RunRequest, uri: String, command: String, changed: String): Lens? {
    val lenses = when (val reply = request(client, holdsRestart = false) { it.textDocumentService.codeLens(CodeLensParams(TextDocumentIdentifier(uri))) }) {
      is Answer -> (reply.value as? List<*>).orEmpty().filterIsInstance<CodeLens>()
      is Unanswered -> {
        // Going on without the lens could run or open statements the user did not mean.
        report(request.file, reply.failure, CODE_LENS, emptyList())
        return null
      }
    }
    // The lens ranges are for the text the request saw; on edited text one can select another statement.
    if (request.document.modificationStamp != request.stamp) {
      notify(project, changed, NotificationType.INFORMATION)
      return null
    }
    return Lens(lensArgAt(lenses, request.caret, command))
  }

  /** Connects, then checks that the server has [file] open: the client, or `null` once the user saw why. */
  private suspend fun ready(file: VirtualFile): LspClient? {
    val client = core.client() ?: return null
    if (!core.awaitServerOpened(file)) {
      notify(project, NOT_OPENED, NotificationType.WARNING)
      return null
    }
    return client
  }

  /** As [ready] for [editor]'s file, then reads what the user asks for in it now. */
  private suspend fun ready(editor: Editor): Pair<LspClient, RunRequest>? {
    val file = editor.virtualFile?.takeIf { it.fileType is DynoTableFileType } ?: return null
    val client = ready(file) ?: return null
    val request = withContext(Dispatchers.EDT) { if (editor.isDisposed) null else RunRequest.of(editor) } ?: return null
    return client to request
  }

  private suspend fun runStatement(client: LspClient, file: VirtualFile, args: Any?) {
    val outcome = Results.getInstance(project).execute {
      when (val reply = request(client, holdsRestart = true) { it.workspaceService.executeCommand(ExecuteCommandParams(RUN_STATEMENT, listOf(args))) }) {
        is Answer -> reply.value
        is Unanswered -> throw CommandFailed(reply.failure)
      }
    }
    when (outcome) {
      RunOutcome.Ok -> {}
      RunOutcome.Busy -> notify(project, BUSY, NotificationType.WARNING)
      RunOutcome.Unreadable -> notify(project, UNREADABLE, NotificationType.ERROR)
      // Any other error is the user's own cancel, which needs no notification.
      is RunOutcome.Failed -> (outcome.error as? CommandFailed)?.let { report(file, it.failure, RUN_STATEMENT, listOf(args), outcome.cancelled) }
    }
  }

  /** One request of the current session: a stop of the session fails it at once. */
  private suspend fun <T> request(client: LspClient, holdsRestart: Boolean, sender: (Lsp4jServer) -> CompletableFuture<T>): Reply {
    val failure = try {
      return Answer(core.machine.trackRun(holdsRestart) {
        var sent = false
        val value = client.sendRequest {
          sent = true
          sender(it)
        }
        // The platform returns `null` without a call when the client is not running; `openInApp` answers `null` too.
        if (!sent) throw NotRunning()
        value
      })
    } catch (e: CancellationException) {
      throw e
    } catch (e: ResponseErrorException) {
      Failure.Error(e.responseError)
    } catch (_: SessionEnded) {
      Failure.SessionEnded
    } catch (_: NotRunning) {
      Failure.NotRunning
    } catch (e: Exception) {
      Failure.Error(ResponseError(0, e.message ?: e.toString(), null))
    }
    return Unanswered(failure)
  }

  private fun report(file: VirtualFile?, failure: Failure, command: String, args: List<Any?>, cancelled: Boolean = false) {
    when (val refusal = refusalOf(failure, cancelled, command)) {
      Refusal.Silent -> {}
      Refusal.ShowStatus -> (core.status as? Status.Disconnected)?.let { core.notify(it.reason) }
      is Refusal.Message -> notify(project, refusal.text, NotificationType.ERROR, refusal.actions.map { it.label to { perform(it, file, args) } })
    }
  }

  private fun perform(action: RefusalAction, file: VirtualFile?, args: List<Any?>) {
    when (action) {
      RefusalAction.SELECT_PROFILE -> core.perform(Action.SELECT_PROFILE)
      RefusalAction.OPEN_APP -> core.perform(Action.OPEN_APP)
      // The refused run's own statement, or the whole document when it had no range.
      RefusalAction.STAGE -> {
        val run = CommandArgs.from(args.firstOrNull())
        val stage = JsonObject().apply {
          addProperty("uri", run.uri)
          run.range?.let { add("range", gson.toJsonTree(it)) }
        }
        // Only a refused run offers Stage, and a run has its file.
        file?.let { launch(it, OPEN_IN_APP, listOf(stage)) }
      }
    }
  }

  private class NotRunning : Exception()

  /** How a failed run reaches [Results.execute], which tells a failure by its exception. */
  private class CommandFailed(val failure: Failure) : Exception()

  companion object {
    fun getInstance(project: Project): ServerCommands = project.service()
  }
}
