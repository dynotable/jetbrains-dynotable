package com.dynotable.editor

import com.dynotable.files.DynoTableFileType
import com.dynotable.protocol.CommandArgs
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.vfs.VirtualFile
import org.eclipse.lsp4j.CodeLens
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

private fun before(a: Position, b: Position) = a.line < b.line || (a.line == b.line && a.character <= b.character)

/** Both ends count: a caret right after a statement's `;` is in that statement. */
fun contains(range: Range, position: Position) = before(range.start, position) && before(position, range.end)

/**
 * The argument of the server's [command] lens whose statement holds [caret], as the server sent it.
 * The lens's own range is an anchor only; the statement's range is in the argument.
 */
fun lensArgAt(lenses: List<CodeLens>, caret: Position, command: String): Any? = lenses.firstNotNullOfOrNull { lens ->
  val arg = lens.command?.takeIf { it.command == command }?.arguments?.firstOrNull() ?: return@firstNotNullOfOrNull null
  val range = CommandArgs.orNull(arg)?.range
  arg.takeIf { range != null && contains(range, caret) }
}

/**
 * What the user asked to run or open, read once the server has the file open: a cold start can take
 * seconds, and the user can type meanwhile.
 */
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
