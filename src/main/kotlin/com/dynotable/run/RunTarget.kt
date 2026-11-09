package com.dynotable.run

import com.dynotable.protocol.CommandArgs
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
