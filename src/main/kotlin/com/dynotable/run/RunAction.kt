package com.dynotable.run

import com.dynotable.editor.ServerCommands
import com.dynotable.files.DynoTableFileType
import com.intellij.openapi.actionSystem.ActionPromoter
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.vfs.VirtualFile
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

/** What the user asked to run, read when the action ran: a later edit must not change it. */
data class RunRequest(
  val file: VirtualFile,
  val document: Document,
  val stamp: Long,
  /** `null` when nothing is selected. */
  val selection: Range?,
  val caret: Position,
) {
  companion object {
    /** `null` outside a DynoTable file. Reads the editor: call it on the EDT. */
    fun of(editor: Editor): RunRequest? {
      val file = editor.virtualFile?.takeIf { it.fileType is DynoTableFileType } ?: return null
      val document = editor.document
      fun position(offset: Int) = document.getLineNumber(offset).let { Position(it, offset - document.getLineStartOffset(it)) }
      val selection = editor.selectionModel.takeIf { it.hasSelection() }?.let { Range(position(it.selectionStart), position(it.selectionEnd)) }
      return RunRequest(file, document, document.modificationStamp, selection, position(editor.caretModel.offset))
    }
  }
}

/** Cmd/Ctrl+Enter: runs the selection, else the statement at the caret, else the whole file. */
class RunAction : DumbAwareAction() {
  override fun getActionUpdateThread() = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = e.project != null && e.getData(CommonDataKeys.EDITOR)?.virtualFile?.fileType is DynoTableFileType
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val request = RunRequest.of(e.getData(CommonDataKeys.EDITOR) ?: return) ?: return
    ServerCommands.getInstance(project).launchRun(request)
  }
}

/** In a DynoTable file the shortcut runs the query; elsewhere it keeps Split Line and the IDE's own Execute. */
class DynoTableActionPromoter : ActionPromoter {
  override fun promote(actions: List<AnAction>, context: DataContext): List<AnAction> {
    if (context.getData(CommonDataKeys.EDITOR)?.virtualFile?.fileType !is DynoTableFileType) return emptyList()
    return actions.filterIsInstance<RunAction>()
  }
}
