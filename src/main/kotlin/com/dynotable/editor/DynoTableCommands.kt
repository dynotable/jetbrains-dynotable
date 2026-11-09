package com.dynotable.editor

import com.dynotable.connect.ConnectCore
import com.dynotable.connect.SessionEnded
import com.dynotable.files.OpenSearchFileType
import com.dynotable.protocol.CommandArgs
import com.dynotable.protocol.ProtocolException
import com.dynotable.refusals.Failure
import com.dynotable.refusals.OPEN_IN_APP
import com.dynotable.refusals.RUN_STATEMENT
import com.dynotable.refusals.Refusal
import com.dynotable.refusals.RefusalAction
import com.dynotable.refusals.notify
import com.dynotable.refusals.refusalOf
import com.dynotable.results.BUSY
import com.dynotable.results.Results
import com.dynotable.results.RunOutcome
import com.dynotable.run.RunRequest
import com.dynotable.run.lensArgAt
import com.dynotable.status.Action
import com.dynotable.status.Status
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.Lsp4jServer
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.customization.LspCommandsSupport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.eclipse.lsp4j.CodeLensParams
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import java.util.concurrent.CompletableFuture

const val NOT_OPENED = "DynoTable has not opened this file. Close and open the file, then try again."
const val CHANGED = "The query changed before the run started. Run it again."
const val UNREADABLE = "DynoTable answered with a result the plugin cannot read."
private const val CODE_LENS = "textDocument/codeLens"
private val gson = Gson()

/** The platform sends a command as a notification, so its answer and its refusal would be lost. */
class DynoTableCommands : LspCommandsSupport() {
  override fun executeCommand(lspClient: LspClient, contextFile: VirtualFile, command: Command) {
    if (command.command.startsWith("dynotable.")) {
      ServerCommands.getInstance(lspClient.project).launch(contextFile, command.command, command.arguments.orEmpty())
    } else {
      super.executeCommand(lspClient, contextFile, command)
    }
  }
}

/** A command's answer. */
class Answer(val value: Any?)

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
    if (command == RUN_STATEMENT && readable(args.firstOrNull())) {
      runStatement(client, file, args.first())
      return null
    }
    return try {
      Answer(request(client, holdsRestart = false) { it.workspaceService.executeCommand(ExecuteCommandParams(command, args)) })
    } catch (e: CommandFailed) {
      report(file, e.failure, command, args)
      null
    }
  }

  /** For a caller on the EDT, which must not wait. */
  fun launchRun(request: RunRequest) {
    cs.launch { run(request) }
  }

  /** Run: the selection, else the server's statement at the caret, else the whole document. */
  suspend fun run(request: RunRequest) {
    val client = ready(request.file) ?: return
    val uri = client.getDocumentIdentifier(request.file).uri
    val args = JsonObject().apply { addProperty("uri", uri) }
    if (request.selection != null) {
      args.add("range", gson.toJsonTree(request.selection))
    } else if (request.file.fileType != OpenSearchFileType) {
      // Without a range the server runs the whole document; for opensearch that is the only form.
      val lenses = try {
        request(client, holdsRestart = false) { it.textDocumentService.codeLens(CodeLensParams(TextDocumentIdentifier(uri))) }
      } catch (e: CommandFailed) {
        // Running the whole document instead could run statements the user did not mean to run.
        report(request.file, e.failure, CODE_LENS, listOf(args))
        return
      }
      // The lens ranges are for the text the request saw; on edited text one can select another statement.
      if (request.document.modificationStamp != request.stamp) {
        notify(project, CHANGED, NotificationType.INFORMATION)
        return
      }
      lensArgAt(lenses.orEmpty(), request.caret, RUN_STATEMENT)?.let { CommandArgs.from(it).range }?.let { args.add("range", gson.toJsonTree(it)) }
    }
    runStatement(client, request.file, args)
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

  private suspend fun runStatement(client: LspClient, file: VirtualFile, args: Any?) {
    val outcome = Results.getInstance(project).execute {
      request(client, holdsRestart = true) { it.workspaceService.executeCommand(ExecuteCommandParams(RUN_STATEMENT, listOf(args))) }
    }
    when (outcome) {
      RunOutcome.Ok -> {}
      RunOutcome.Busy -> notify(project, BUSY, NotificationType.WARNING)
      RunOutcome.Unreadable -> notify(project, UNREADABLE, NotificationType.ERROR)
      is RunOutcome.Failed -> {
        val failure = (outcome.error as? CommandFailed)?.failure ?: Failure.Error(ResponseError(0, outcome.error.message ?: outcome.error.toString(), null))
        report(file, failure, RUN_STATEMENT, listOf(args), outcome.cancelled)
      }
    }
  }

  /** One request of the current session: a stop of the session fails it at once. */
  private suspend fun <T> request(client: LspClient, holdsRestart: Boolean, sender: (Lsp4jServer) -> CompletableFuture<T>): T? {
    val failure = try {
      return core.machine.trackRun(holdsRestart) {
        var sent = false
        val value = client.sendRequest {
          sent = true
          sender(it)
        }
        // The platform returns `null` without a call when the client is not running; `openInApp` answers `null` too.
        if (!sent) throw NotRunning()
        value
      }
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
    throw CommandFailed(failure)
  }

  private fun report(file: VirtualFile, failure: Failure, command: String, args: List<Any?>, cancelled: Boolean = false) {
    when (val refusal = refusalOf(failure, cancelled, command)) {
      Refusal.Silent -> {}
      Refusal.ShowStatus -> (core.status as? Status.Disconnected)?.let { core.notify(it.reason) }
      is Refusal.Message -> notify(project, refusal.text, NotificationType.ERROR, refusal.actions.map { it.label to { perform(it, file, args) } })
    }
  }

  private fun perform(action: RefusalAction, file: VirtualFile, args: List<Any?>) {
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
        launch(file, OPEN_IN_APP, listOf(stage))
      }
    }
  }

  private class NotRunning : Exception()

  private class CommandFailed(val failure: Failure) : Exception()

  private fun readable(arg: Any?) = try {
    CommandArgs.from(arg)
    true
  } catch (_: ProtocolException) {
    false
  }

  companion object {
    fun getInstance(project: Project): ServerCommands = project.service()
  }
}
