package com.dynotable.editor

import com.dynotable.protocol.RUN_STATEMENT
import com.google.gson.Gson
import org.eclipse.lsp4j.CodeLens
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.InlayHint
import org.eclipse.lsp4j.InlayHintLabelPart
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Where a plan goes in the lens row. */
class PlanLensesTest {
  private fun span(a: Int, b: Int, c: Int, d: Int) = Range(Position(a, b), Position(c, d))

  private fun run(anchor: Range, statement: Range) =
    CodeLens(anchor, Command("Run", RUN_STATEMENT, listOf(Gson().toJsonTree(mapOf("uri" to "file:///q", "range" to statement)))), null)

  // Two statements: a plan must land on its own statement's lens row, not the first one's.
  private val lenses = listOf(run(span(0, 0, 0, 0), span(0, 0, 0, 23)), run(span(1, 0, 1, 0), span(1, 0, 2, 10)))

  private fun hint(line: Int, character: Int, label: String) = InlayHint(Position(line, character), Either.forLeft(label))

  // The server anchors a plan at its statement's last character: the range end must count.
  @Test
  fun `moves a plan at the end of a statement to that statement's lens row`() {
    assertEquals(listOf(PlanLens(span(1, 0, 1, 0), "Scan customers")), planLenses(lenses, listOf(hint(2, 10, "Scan customers"))))
  }

  @Test
  fun `puts a plan that no statement holds at the start of its own line`() {
    assertEquals(listOf(PlanLens(span(4, 0, 4, 0), "Query orders")), planLenses(lenses, listOf(hint(4, 7, " Query orders"))))
  }

  @Test
  fun `joins the parts of a label`() {
    val label = Either.forRight<String, List<InlayHintLabelPart>>(listOf(InlayHintLabelPart("Query orders"), InlayHintLabelPart(" · ~2 RCU")))
    assertEquals("Query orders · ~2 RCU", planLenses(lenses, listOf(InlayHint(Position(0, 23), label))).single().title)
  }
}
