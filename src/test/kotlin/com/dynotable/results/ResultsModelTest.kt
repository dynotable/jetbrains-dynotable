package com.dynotable.results

import com.dynotable.protocol.RunResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource

/** The results view and its copies, on answers decoded as lsp4j decodes them: maps, lists, Double numbers. */
class ResultsModelTest {
  private fun result(vararg changes: Pair<String, Any?>): RunResult = RunResult.from(
    linkedMapOf<String, Any?>(
      "columns" to emptyList<String>(),
      "rows" to emptyList<Any>(),
      "returnedRows" to 0.0,
      "totalRows" to null,
      "truncated" to false,
      "approximateBytes" to 0.0,
      "elapsedMs" to 5.0,
      "executedStatement" to "SELECT * FROM \"t\"",
      "effectiveRowCap" to 500.0,
    ).apply { putAll(changes) },
  )

  /** A one-column result that holds [cell]. */
  private fun one(cell: Any?) = result("columns" to listOf("v"), "rows" to listOf(mapOf("v" to cell)))

  // The server's cap, not the 500 default; Double counts must not print as `3.0`; an absent itemsScanned leaves no trace.
  @Test
  fun `an exact total shows beside a truncated result, with the cap the server applied`() {
    assertEquals(
      "3 of 120 rows · limited to 3 rows · 5 ms",
      summary(result("returnedRows" to 3.0, "totalRows" to 120.0, "truncated" to true, "effectiveRowCap" to 3.0)),
    )
  }

  // A null total is unknown: the returned count must not stand in for it.
  @Test
  fun `a null total says the total is unknown`() {
    assertEquals(
      "3 rows · limited to 3 rows, total unknown · 150 items scanned · 207 ms",
      summary(result("returnedRows" to 3.0, "truncated" to true, "effectiveRowCap" to 3.0, "itemsScanned" to 150.0, "elapsedMs" to 207.0)),
    )
  }

  // A path reader would answer "B".
  @Test
  fun `a qualified column is a literal key`() {
    val shown = resultView(result("columns" to listOf("o.orderId"), "rows" to listOf(mapOf("o.orderId" to "A", "o" to mapOf("orderId" to "B")))))
    assertEquals(listOf(listOf("A")), shown.cells)
  }

  // A SQL Set keeps its type tag, also inside a map, so it does not read as a list. The columns are not
  // in sorted order, so a sorted header fails.
  @Test
  fun `the column order stays, a missing key is empty, and nested values show as JSON`() {
    val shown = resultView(
      result(
        "columns" to listOf("id", "tags", "nestedMap", "scores"),
        "rows" to listOf(
          mapOf(
            "id" to 1.0,
            "tags" to listOf("a", "b"),
            "nestedMap" to mapOf("innerLabel" to "inner", "innerTags" to mapOf("SS" to listOf("inner-a", "inner-b"))),
            "scores" to mapOf("NS" to listOf("10", "20", "30")),
          ),
          mapOf("id" to 2.0),
        ),
      ),
    )
    assertEquals(listOf("id", "tags", "nestedMap", "scores"), shown.columns)
    assertEquals(
      listOf(
        listOf("1", """["a","b"]""", """{"innerLabel":"inner","innerTags":{"SS":["inner-a","inner-b"]}}""", """{"NS":["10","20","30"]}"""),
        listOf("2", "", "", ""),
      ),
      shown.cells,
    )
  }

  // Gson prints a decoded nested 1 as 1.0, and escapes `=`, `<` and U+2028.
  @Test
  fun `a nested number and nested strings show as JSON stringify writes them, in a cell and in Copy JSON`() {
    val value = listOf(mapOf("qty" to 1.0), "a=b<c", "x\u2028y", "q\"\\\n")
    val cell = """[{"qty":1},"a=b<c","x${'\u2028'}y","q\"\\\n"]"""
    assertEquals(cell, resultView(one(value)).cells.single().single())
    assertEquals("[\n  {\n    \"v\": [\n      {\n        \"qty\": 1\n      },\n      \"a=b<c\",\n      \"x\u2028y\",\n      \"q\\\"\\\\\\n\"\n    ]\n  }\n]", toJson(one(value)))
  }

  // A number JSON cannot hold arrives as {"N": "<exact>"}; the copy must not turn it into a lossy number.
  @Test
  fun `an exact-decimal number stays its tagged text in Copy JSON`() {
    assertEquals("[\n  {\n    \"n\": {\n      \"N\": \"12345678901234567890.123\"\n    }\n  }\n]", toJson(result("columns" to listOf("n"), "rows" to listOf(mapOf("n" to mapOf("N" to "12345678901234567890.123"))))))
  }

  @ParameterizedTest
  @MethodSource("formulaStarts")
  fun `CSV guards a cell that starts with a formula character`(cell: String, expected: String) {
    assertEquals("v\r\n$expected", toCsv(one(cell)))
  }

  @Test
  fun `CSV guards a header cell, and guards before it quotes`() {
    assertEquals(
      "\"'=HYPERLINK(\"\"x\"\")\",b\r\n,\"'=1,2\"",
      toCsv(result("columns" to listOf("=HYPERLINK(\"x\")", "b"), "rows" to listOf(mapOf("b" to "=1,2")))),
    )
  }

  // Each passes a loose "looks numeric" check.
  @ParameterizedTest
  @ValueSource(strings = ["-1+1", "+1)", "-2+cmd|'/C calc'!A0", "-1.5abc"])
  fun `CSV guards a bypass form`(cell: String) {
    assertEquals("v\r\n'$cell", toCsv(one(cell)))
  }

  @ParameterizedTest
  @ValueSource(strings = ["-12345678901.5", "-1e5"])
  fun `CSV leaves a number literal intact`(cell: String) {
    assertEquals("v\r\n$cell", toCsv(one(cell)))
  }

  companion object {
    @JvmStatic
    fun formulaStarts(): List<Arguments> = listOf(
      Arguments.of("=x", "'=x"),
      Arguments.of("+x", "'+x"),
      Arguments.of("-x", "'-x"),
      Arguments.of("@x", "'@x"),
      Arguments.of("\tx", "'\tx"),
      Arguments.of("\rx", "\"'\rx\""),
      Arguments.of("\nx", "\"'\nx\""),
    )
  }
}
