package com.dynotable.editor

import com.intellij.codeInsight.FileModificationService
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.customization.LspIntentionAction
import com.intellij.platform.lsp.util.applyTextEdits
import com.intellij.psi.PsiFile
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.TextEdit

/**
 * The platform's own code action is hidden in a file outside project content, such as a scratch file
 * or a `.partiql` file outside the project. This one works there too. It applies the server's edits
 * to the file it was asked for, and only when the action edits nothing else.
 */
class DynoTableQuickFix(client: LspClient, private val action: CodeAction) : LspIntentionAction(client, action) {
  override fun isAvailable(project: Project, editor: Editor, psiFile: PsiFile): Boolean {
    val file = psiFile.virtualFile ?: return false
    return action.disabled == null && (action.edit != null || action.command != null) && edits(file) != null
  }

  override fun invoke(project: Project, editor: Editor, psiFile: PsiFile) {
    val file = psiFile.virtualFile ?: return
    val edits = edits(file) ?: return
    if (!FileModificationService.getInstance().prepareVirtualFilesForWrite(project, listOf(file))) return
    if (edits.isNotEmpty()) WriteAction.run<Throwable> { applyTextEdits(editor.document, edits) }
    val command = action.command ?: return
    DynoTableCommands.executeCommand(lspClient, file, command)
  }

  override fun generatePreview(project: Project, editor: Editor, psiFile: PsiFile): IntentionPreviewInfo {
    val edits = psiFile.originalFile.virtualFile?.let(::edits)
    if (edits.isNullOrEmpty()) return IntentionPreviewInfo.EMPTY
    applyTextEdits(psiFile.viewProvider.document, edits)
    return IntentionPreviewInfo.DIFF
  }

  /** The edits of [file]; `null` when the action also edits another file, or edits an older version. */
  private fun edits(file: VirtualFile): List<TextEdit>? {
    val edit = action.edit ?: return emptyList()
    val uri = lspClient.descriptor.getFileUri(file)
    // The client advertises documentChanges, so the spec says to ignore changes when both are sent.
    val documentChanges = edit.documentChanges ?: run {
      val changes = edit.changes.orEmpty()
      return if (changes.keys.any { it != uri }) null else changes[uri].orEmpty()
    }
    val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
    return documentChanges.flatMap { change ->
      val text = change.left?.takeIf { it.textDocument.uri == uri } ?: return null
      if (text.textDocument.version?.let { it != lspClient.getDocumentVersion(document) } == true) return null
      text.edits.map { it.left ?: return null }
    }
  }
}
