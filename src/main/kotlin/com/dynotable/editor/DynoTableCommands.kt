package com.dynotable.editor

import com.dynotable.connect.ConnectCore
import com.dynotable.connect.SessionEnded
import com.dynotable.protocol.CommandArgs
import com.dynotable.refusals.Failure
import com.dynotable.refusals.OPEN_IN_APP
import com.dynotable.refusals.RUN_STATEMENT
import com.dynotable.refusals.Refusal
import com.dynotable.refusals.RefusalAction
import com.dynotable.refusals.notify
import com.dynotable.refusals.refusalOf
import com.dynotable.status.Action
import com.dynotable.status.Status
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.customization.LspCommandsSupport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError

const val NOT_OPENED = "DynoTable has not opened this file. Close and open the file, then try again."

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
    val client = core.client() ?: return null
    if (!core.awaitServerOpened(file)) {
      notify(project, NOT_OPENED, NotificationType.WARNING)
      return null
    }
    val failure = try {
      return core.machine.trackRun(holdsRestart = command == RUN_STATEMENT) {
        var sent = false
        val value = client.sendRequest {
          sent = true
          it.workspaceService.executeCommand(ExecuteCommandParams(command, args))
        }
        // The platform returns `null` without a call when the client is not running; `openInApp` answers `null` too.
        if (!sent) throw NotRunning()
        Answer(value)
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
    report(file, failure, command, args)
    return null
  }

  private fun report(file: VirtualFile, failure: Failure, command: String, args: List<Any?>) {
    when (val refusal = refusalOf(failure, false, command)) {
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
          run.range?.let { add("range", Gson().toJsonTree(it)) }
        }
        launch(file, OPEN_IN_APP, listOf(stage))
      }
    }
  }

  private class NotRunning : Exception()

  companion object {
    fun getInstance(project: Project): ServerCommands = project.service()
  }
}
