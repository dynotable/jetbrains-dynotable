package com.dynotable.run

import com.dynotable.editor.ServerCommands
import com.dynotable.files.DynoTableFileType
import com.intellij.openapi.actionSystem.ActionPromoter
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.project.DumbAwareAction

/** Cmd/Ctrl+Enter: runs the selection, else the statement at the caret, else the whole file. */
class RunAction : DumbAwareAction() {
  override fun getActionUpdateThread() = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = e.project != null && e.getData(CommonDataKeys.EDITOR)?.virtualFile?.fileType is DynoTableFileType
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    ServerCommands.getInstance(project).launchRun(e.getData(CommonDataKeys.EDITOR) ?: return)
  }
}

/**
 * In a DynoTable file the shortcuts run and open the query; elsewhere they keep Split Line, Complete
 * Current Statement and the IDE's own Execute.
 */
class DynoTableActionPromoter : ActionPromoter {
  override fun promote(actions: List<AnAction>, context: DataContext): List<AnAction> {
    if (context.getData(CommonDataKeys.EDITOR)?.virtualFile?.fileType !is DynoTableFileType) return emptyList()
    return actions.filter { it is RunAction || it is OpenAction }
  }
}
