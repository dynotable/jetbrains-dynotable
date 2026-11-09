package com.dynotable.editor

import com.dynotable.protocol.CommandArgs
import com.dynotable.protocol.OPEN_IN_APP
import com.dynotable.protocol.RUN_STATEMENT
import com.google.gson.Gson
import org.eclipse.lsp4j.CodeLens
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The statement a caret Run picks from the server's lenses. */
class RunTargetTest {
  private fun line(n: Int, length: Int) = Range(Position(n, 0), Position(n, length))

  /** As the server sends them: an anchor range, the statement's range in the argument. */
  private fun lens(command: String, statement: Range) =
    CodeLens(Range(statement.start, statement.start), Command("x", command, listOf(Gson().toJsonTree(mapOf("uri" to "file:///q", "range" to statement)))), null)

  // The Open lens comes first and also holds the caret, with another range.
  private val lenses = listOf(lens(OPEN_IN_APP, line(0, 30)), lens(RUN_STATEMENT, line(0, 23)), lens(RUN_STATEMENT, line(1, 26)))

  private fun rangeAt(line: Int, character: Int) = lensArgAt(lenses, Position(line, character), RUN_STATEMENT)?.let { CommandArgs.from(it).range }

  @Test
  fun `a caret in the second statement picks the second statement`() {
    assertEquals(line(1, 26), rangeAt(1, 5))
  }

  // The statement ranges include their end, so a caret right after the `;` runs that statement.
  @Test
  fun `a caret at the end of the first statement picks the first statement, not another command's lens`() {
    assertEquals(line(0, 23), rangeAt(0, 23))
  }

  @Test
  fun `a caret outside every statement picks nothing`() {
    assertNull(rangeAt(2, 0))
  }
}
