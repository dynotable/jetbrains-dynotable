package com.dynotable.editor

import com.dynotable.protocol.CommandArgs
import com.dynotable.refusals.RUN_STATEMENT
import com.dynotable.run.contains
import org.eclipse.lsp4j.CodeLens
import org.eclipse.lsp4j.InlayHint
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

/** A plan as the lens row shows it, at a server lens's range or at the start of a line. */
data class PlanLens(val anchor: Range, val title: String)

/**
 * The server puts each plan at the end of its statement, where it sits after the caret while the
 * user types. Each one moves to the lens row of the Run lens whose statement holds it, or to the
 * start of its own line when no statement holds it.
 */
fun planLenses(lenses: List<CodeLens>, hints: List<InlayHint>): List<PlanLens> {
  val statements = lenses.mapNotNull { lens ->
    val arg = lens.command?.takeIf { it.command == RUN_STATEMENT }?.arguments?.firstOrNull()
    CommandArgs.orNull(arg)?.range?.let { lens.range to it }
  }
  return hints.map { hint ->
    val owner = statements.firstOrNull { (_, statement) -> contains(statement, hint.position) }?.first
    val start = Position(hint.position.line, 0)
    val label = hint.label
    val text = if (label.isLeft) label.left else label.right.joinToString("") { it.value }
    PlanLens(owner ?: Range(start, start), text.trim())
  }
}
