package com.dynotable.protocol

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import org.eclipse.lsp4j.CodeLens
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.ProgressParams
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.lang.reflect.Type
import java.nio.file.Files
import java.nio.file.Path

/**
 * Every `contract/` fixture must parse by its manifest shape, strictly: a fixture key with no Kotlin or
 * lsp4j field fails, so a renamed or new server field is seen here before a user sees it.
 */
class ContractTest {
  private val dir: Path = Path.of(System.getProperty("dynotable.projectDir"), "contract")
  private val gson = MessageJsonHandler(emptyMap()).gson

  @TestFactory
  fun `each fixture parses strictly by its manifest shape`(): List<DynamicTest> {
    val manifest = JsonParser.parseString(Files.readString(dir.resolve("manifest.json"))).asJsonObject
    return manifest.entrySet().map { (file, entry) ->
      DynamicTest.dynamicTest(file) {
        parse(entry.asJsonObject.get("guard").asString, JsonParser.parseString(Files.readString(dir.resolve(file))))
      }
    }
  }

  private fun parse(guard: String, json: JsonElement) {
    when (guard) {
      "runResult" -> RunResult.from(json, strict = true)
      "profileList" -> ProfileRow.listFrom(json, strict = true)
      "listTables" -> ListTablesResult.from(json, strict = true)
      "listIndices" -> ListIndicesResult.from(json, strict = true)
      "string" -> assertTrue(json.isJsonPrimitive && json.asJsonPrimitive.isString, "expected a string")
      "initializeResult" -> lsp4j<InitializeResult>(json)
      "progressList" -> lsp4j<List<ProgressParams>>(json)
      "showMessage" -> lsp4j<MessageParams>(json)
      "codeLensList" -> lsp4j<List<CodeLens>>(json).forEach { lens ->
        CommandArgs.from(lens.command.arguments.single(), strict = true)
      }
      "responseError" -> lsp4j<ResponseError>(json).data?.let { FailureData.from(it, strict = true) }
      else -> throw AssertionError("no parser for guard \"$guard\"")
    }
  }

  /** Decodes as the platform's lsp4j does. A key that lsp4j drops does not come back in the round trip. */
  private inline fun <reified T> lsp4j(json: JsonElement): T {
    val type: Type = object : TypeToken<T>() {}.type
    val value: T = gson.fromJson(json, type)
    assertEquals(json, gson.toJsonTree(value, type), "a key has no lsp4j field")
    return value
  }
}
