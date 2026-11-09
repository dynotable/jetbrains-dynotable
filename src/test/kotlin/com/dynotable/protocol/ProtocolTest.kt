package com.dynotable.protocol

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class ProtocolTest {
  // As lsp4j decodes an executeCommand answer: maps and lists, every number a Double.
  private fun runResult(vararg changes: Pair<String, Any?>): Map<String, Any?> = linkedMapOf<String, Any?>(
    "columns" to listOf("id"),
    "rows" to listOf(mapOf("id" to 1.0)),
    "returnedRows" to 1.0,
    "totalRows" to null,
    "truncated" to false,
    "approximateBytes" to 12.0,
    "elapsedMs" to 3.0,
    "executedStatement" to "SELECT * FROM \"t\"",
    "effectiveRowCap" to 500.0,
  ).apply { changes.forEach { (key, value) -> if (value == REMOVE) remove(key) else put(key, value) } }

  private val profile = mapOf(
    "id" to "p1", "name" to "Local", "slug" to "local", "kind" to "offline", "bindable" to true,
    "engines" to mapOf("dynamodb" to true, "opensearch" to false),
  )

  private fun problem(block: () -> Unit) = assertThrows<ProtocolException>(block).message

  // A port file in a log line or an exception message would give the token to whoever reads the log.
  @Test
  fun `a port file prints its port and pid, never its token`() {
    assertEquals("PortFile(port=41120, pid=111)", PortFile(41120, "s3cret-token", 111).toString())
  }

  // The plugin connects to the port and sends the token: a port out of range, an empty token or a
  // pid that names no process must not read as a port file.
  @ParameterizedTest
  @CsvSource(
    delimiter = '|',
    value = [
      "{\"port\":0,\"token\":\"t\"}",
      "{\"port\":65536,\"token\":\"t\"}",
      "{\"port\":1.5,\"token\":\"t\"}",
      "{\"port\":41120,\"token\":\"\"}",
      "{\"port\":41120,\"token\":\"t\",\"pid\":0}",
    ],
  )
  fun `a port file with a bad field is refused`(text: String) {
    assertThrows<ProtocolException> { PortFile.from(JsonParser.parseString(text)) }
  }

  // A missing total would read as "unknown" only by accident, and a string count would print as a total.
  @Test
  fun `a missing required key and a wrong primitive type fail`() {
    assertEquals("$.totalRows: missing", problem { RunResult.from(runResult("totalRows" to REMOVE)) })
    assertEquals("$.returnedRows: expected number", problem { RunResult.from(runResult("returnedRows" to "1")) })
  }

  // The server's surface is additive: a lenient reader that refuses a new key or enum value breaks every
  // installed plugin on the next app release. The strict reader must refuse them, or the contract test
  // cannot see a fixture key that has no Kotlin field.
  @Test
  fun `lenient accepts an extra key and new enum values, strict refuses them`() {
    val extra = runResult("cursor" to "x")
    val newKind = listOf(profile + ("kind" to "serverless"))
    val newReason = listOf(profile + ("bindable" to false) + ("reason" to "expired"))
    val newCode = mapOf("code" to "quota-exceeded")

    assertEquals(listOf("id"), RunResult.from(extra).columns)
    assertEquals("serverless", ProfileRow.listFrom(newKind).single().kind)
    assertEquals("expired", ProfileRow.listFrom(newReason).single().reason)
    assertEquals("quota-exceeded", FailureData.from(newCode).code)

    assertEquals("$.cursor: unexpected key", problem { RunResult.from(extra, strict = true) })
    assertEquals("$[0].kind: unknown value \"serverless\"", problem { ProfileRow.listFrom(newKind, strict = true) })
    assertEquals("$[0].reason: unknown value \"expired\"", problem { ProfileRow.listFrom(newReason, strict = true) })
    assertEquals("$.code: unknown value \"quota-exceeded\"", problem { FailureData.from(newCode, strict = true) })
  }

  // Kotlin prints the Double that lsp4j decodes as `1.0` and `1.0E21`; the app and VS Code show `1` and `1e+21`.
  @ParameterizedTest
  @CsvSource(
    "1.0, 1",
    "1.5, 1.5",
    "-1.5, -1.5",
    "1e20, 100000000000000000000",
    "1e21, 1e+21",
    "0.000001, 0.000001",
    "1e-7, 1e-7",
    "-0.0, 0",
  )
  fun `numbers format as JavaScript String(number)`(value: Double, expected: String) {
    assertEquals(expected, jsNumber(value))
  }

  private companion object {
    val REMOVE = Any()
  }
}
