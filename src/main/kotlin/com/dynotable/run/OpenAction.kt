package com.dynotable.run

import com.dynotable.editor.ServerCommands
import com.dynotable.files.DynoTableFileType
import com.dynotable.files.OpenSearchFileType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAwareAction

/** Cmd/Ctrl+Shift+Enter: opens the statement at the caret in DynoTable, with the server's Open lens argument. */
class OpenAction : DumbAwareAction() {
  override fun getActionUpdateThread() = ActionUpdateThread.BGT

  // The server refuses Open for every opensearch document.
  override fun update(e: AnActionEvent) {
    val type = e.getData(CommonDataKeys.EDITOR)?.virtualFile?.fileType
    e.presentation.isEnabledAndVisible = e.project != null && type is DynoTableFileType && type != OpenSearchFileType
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    ServerCommands.getInstance(project).launchOpen(e.getData(CommonDataKeys.EDITOR) ?: return)
  }
}
