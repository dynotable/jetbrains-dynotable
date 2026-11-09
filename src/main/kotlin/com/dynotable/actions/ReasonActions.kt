package com.dynotable.actions

import com.dynotable.connect.ConnectCore
import com.dynotable.status.Action
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

/** Clears a latch and connects anew. */
class ReconnectAction : ReasonAction(Action.RECONNECT)

class OpenDocsAction : ReasonAction(Action.OPEN_DOCS)

abstract class ReasonAction(private val action: Action) : DumbAwareAction() {
  override fun getActionUpdateThread() = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = e.project != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    ConnectCore.getInstance(e.project ?: return).perform(action)
  }
}
