package com.dynotable.refusals

import com.dynotable.connect.failureCode
import com.dynotable.status.Action
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError

const val RUN_STATEMENT = "dynotable.runStatement"
const val OPEN_IN_APP = "dynotable.openInApp"
const val VALIDATE_QUERY = "dynotable.validateQuery"

enum class RefusalAction(val label: String) {
  SELECT_PROFILE(Action.SELECT_PROFILE.label),
  OPEN_APP(Action.OPEN_APP.label),
  STAGE("Stage in DynoTable"),
}

/** Why a server command did not answer. */
sealed interface Failure {
  /** The server's error answer, or another error with code `0`. */
  data class Error(val error: ResponseError) : Failure

  /** The platform sent nothing: the client was not running. */
  data object NotRunning : Failure

  /** The session ended before the answer: a stopped client never completes its requests. */
  data object SessionEnded : Failure
}

sealed interface Refusal {
  data object Silent : Refusal

  /** Show the connection's current reason: "not running" says nothing the user can act on. */
  data object ShowStatus : Refusal

  /** [text] is the server's sentence, shown as plain text. */
  data class Message(val text: String, val actions: List<RefusalAction>) : Refusal
}

/** Open DynoTable is offered for these, never implied as the fix. */
private val ACTIONS = mapOf(
  "profile-required" to listOf(RefusalAction.SELECT_PROFILE),
  "signed-out" to listOf(RefusalAction.OPEN_APP),
  "profile-window-required" to listOf(RefusalAction.OPEN_APP),
  "reauth-required" to listOf(RefusalAction.OPEN_APP),
)

/** [cancelled]: the user cancelled this run; the server's refusal of it is not news. */
fun refusalOf(failure: Failure, cancelled: Boolean, command: String): Refusal = when {
  cancelled -> Refusal.Silent
  else -> when (failure) {
    Failure.NotRunning -> Refusal.ShowStatus
    // The session's end is announced once, by its latched reason.
    Failure.SessionEnded -> Refusal.Silent
    is Failure.Error -> {
      val code = failureCode(failure.error)
      // Stage sends openInApp: if its own refusal offered Stage again, each click would show it again.
      val actions = if (code == "stage-in-app") listOfNotNull(RefusalAction.STAGE.takeIf { command == RUN_STATEMENT }) else ACTIONS[code].orEmpty()
      Refusal.Message(failure.error.message, actions)
    }
  }
}

