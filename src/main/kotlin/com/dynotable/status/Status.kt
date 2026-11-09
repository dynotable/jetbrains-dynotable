package com.dynotable.status

import com.intellij.openapi.util.text.StringUtil

/** What the status bar shows. [Connected] and [Unbound] carry the attempt whose session serves. */
sealed interface Status {
  data object Hidden : Status

  /** [waitingForRun]: a restart waits until the running query ends. */
  data class Starting(val waitingForRun: Boolean = false) : Status

  data class Connected(val attempt: Int) : Status

  /** A live session with no profile: it serves the editor, but runs and listings need a profile. */
  data class Unbound(val attempt: Int) : Status

  data class Disconnected(val reason: Reason) : Status
}

/** Reconnect is always one click away: a fix such as a licence activation writes no new port file. */
val Status.actions: List<Action>
  get() = when (this) {
    is Status.Unbound -> Reason.UNBOUND.actions
    is Status.Disconnected -> reason.actions
    else -> listOf(Action.RECONNECT)
  }

/** [requested]: the name of the profile asked for at `initialize`. The server does not confirm it. */
fun Status.text(requested: String?): String = when (this) {
  Status.Hidden -> ""
  is Status.Starting -> "DynoTable: connecting"
  is Status.Connected -> if (requested == null) "DynoTable" else "DynoTable: $requested"
  is Status.Unbound -> "DynoTable: no profile"
  is Status.Disconnected -> "DynoTable: disconnected"
}

/** The platform shows a tooltip as HTML. */
fun Status.tooltip(requested: String?): String = when (this) {
  Status.Hidden -> ""
  is Status.Starting -> if (waitingForRun) "DynoTable reconnects when the current run ends." else "Connecting to DynoTable."
  is Status.Connected -> if (requested == null) "Connected to DynoTable." else "Connected to DynoTable. Requested profile: ${StringUtil.escapeXmlEntities(requested)}."
  is Status.Unbound -> Reason.UNBOUND.sentence
  is Status.Disconnected -> reason.sentence
}
