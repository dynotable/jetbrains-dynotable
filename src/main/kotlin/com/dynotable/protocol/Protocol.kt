package com.dynotable.protocol

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import java.math.BigDecimal
import kotlin.math.abs

/*
 * Wire types of the DynoTable Language Server's custom surface (PROTOCOL.md), with their parsers.
 *
 * Each parser takes a JsonElement or the Object tree lsp4j decodes (maps, lists, Double numbers).
 * The server's surface is additive, so by default an extra key or a new enum value is accepted.
 * `strict = true` refuses both: the contract fixtures use it to see drift first.
 */

/** The value does not have the shape the protocol gives. The message names the path. */
class ProtocolException(message: String) : RuntimeException(message)

data class PortFile(val port: Int, val token: String, val pid: Long?) {
  // A data class prints its fields; the token must never reach a log through toString.
  override fun toString() = "PortFile(port=$port, pid=$pid)"

  companion object {
    fun from(value: Any?, strict: Boolean = false): PortFile = read(value, strict) {
      PortFile(
        port = required("port") { e, p -> integer(e, p, 1, 65535).toInt() },
        token = required("token", ::nonEmpty),
        pid = optional("pid") { e, p -> integer(e, p, 1, MAX_SAFE_INTEGER) },
      )
    }
  }
}

data class CommandArgs(
  val uri: String,
  val range: Range?,
  val rowCap: Double?,
  val index: String?,
  val target: String?,
) {
  companion object {
    fun from(value: Any?, strict: Boolean = false): CommandArgs = read(value, strict) {
      CommandArgs(
        uri = required("uri", ::string),
        range = optional("range", range(strict)),
        rowCap = optional("rowCap", ::number),
        index = optional("index", ::string),
        target = optional("target", ::string),
      )
    }
  }
}

data class RunResult(
  val columns: List<String>,
  /** Cell values stay as sent; the results view formats them. */
  val rows: List<JsonObject>,
  val returnedRows: Double,
  /** `null`: the total is unknown. */
  val totalRows: Double?,
  val truncated: Boolean,
  val itemsScanned: Double?,
  val approximateBytes: Double,
  val elapsedMs: Double,
  val executedStatement: String,
  val effectiveRowCap: Double,
) {
  companion object {
    fun from(value: Any?, strict: Boolean = false): RunResult = read(value, strict) {
      RunResult(
        columns = required("columns") { e, p -> list(e, p, ::string) },
        rows = required("rows") { e, p -> list(e, p) { row, rowPath -> row as? JsonObject ?: fail(rowPath, "expected object") } },
        returnedRows = required("returnedRows", ::number),
        totalRows = required("totalRows") { e, p -> if (e.isJsonNull) null else number(e, p) },
        truncated = required("truncated", ::boolean),
        itemsScanned = optional("itemsScanned", ::number),
        approximateBytes = required("approximateBytes", ::number),
        elapsedMs = required("elapsedMs", ::number),
        executedStatement = required("executedStatement", ::string),
        effectiveRowCap = required("effectiveRowCap", ::number),
      )
    }
  }
}

data class Engines(val dynamodb: Boolean, val opensearch: Boolean)

data class ProfileRow(
  val id: String,
  val name: String,
  val slug: String,
  /** `online` or `offline`; a new kind is kept as sent. */
  val kind: String,
  val bindable: Boolean,
  /** Why the row cannot bind: `incomplete`, or a new reason as sent. */
  val reason: String?,
  val engines: Engines,
) {
  companion object {
    fun listFrom(value: Any?, strict: Boolean = false): List<ProfileRow> = list(toJsonElement(value), "$") { e, p ->
      read(e, p, strict) {
        ProfileRow(
          id = required("id", ::string),
          name = required("name", ::string),
          slug = required("slug", ::string),
          kind = required("kind", oneOf(strict, "online", "offline")),
          bindable = required("bindable", ::boolean),
          reason = optional("reason", oneOf(strict, "incomplete")),
          engines = required("engines") { engines, enginesPath ->
            read(engines, enginesPath, strict) {
              Engines(dynamodb = required("dynamodb", ::boolean), opensearch = required("opensearch", ::boolean))
            }
          },
        )
      }
    }
  }
}

data class ListingFailure(val message: String)

data class ListTablesResult(val tables: List<String>, val tableListingFailed: ListingFailure?) {
  companion object {
    fun from(value: Any?, strict: Boolean = false): ListTablesResult = read(value, strict) {
      ListTablesResult(
        tables = required("tables") { e, p -> list(e, p, ::string) },
        tableListingFailed = optional("tableListingFailed") { e, p -> listingFailure(e, p, strict) },
      )
    }
  }
}

data class IndexRow(val name: String, val target: String)

data class UnreachableTarget(val target: String, val message: String)

data class ListIndicesResult(
  val indices: List<IndexRow>,
  val unreachable: List<UnreachableTarget>,
  val indexListingFailed: ListingFailure?,
  val partial: ListingFailure?,
) {
  companion object {
    fun from(value: Any?, strict: Boolean = false): ListIndicesResult = read(value, strict) {
      ListIndicesResult(
        indices = required("indices") { e, p ->
          list(e, p) { row, rowPath -> read(row, rowPath, strict) { IndexRow(required("name", ::string), required("target", ::string)) } }
        },
        unreachable = required("unreachable") { e, p ->
          list(e, p) { row, rowPath ->
            read(row, rowPath, strict) { UnreachableTarget(required("target", ::string), required("message", ::string)) }
          }
        },
        indexListingFailed = optional("indexListingFailed") { e, p -> listingFailure(e, p, strict) },
        partial = optional("partial") { e, p -> listingFailure(e, p, strict) },
      )
    }
  }
}

/** The `data` of a `-32803` refusal. */
data class FailureData(
  /** One of PROTOCOL.md › Errors, or a new code as sent. */
  val code: String,
) {
  companion object {
    private val CODES = arrayOf(
      "profile-required", "profile-window-required", "reauth-required", "premium-required", "signed-out",
      "stage-in-app", "busy", "planner-refused", "run-failed",
    )

    fun from(value: Any?, strict: Boolean = false): FailureData = read(value, strict) {
      FailureData(required("code", oneOf(strict, *CODES)))
    }
  }
}

/** Converts the Object tree lsp4j decodes (maps, lists, Double numbers) to a JsonElement. */
fun toJsonElement(value: Any?): JsonElement = when (value) {
  null -> JsonNull.INSTANCE
  is JsonElement -> value
  is String -> JsonPrimitive(value)
  is Boolean -> JsonPrimitive(value)
  is Number -> JsonPrimitive(value)
  is Map<*, *> -> JsonObject().apply { value.forEach { (key, item) -> add(key.toString(), toJsonElement(item)) } }
  is Iterable<*> -> JsonArray().apply { value.forEach { add(toJsonElement(it)) } }
  else -> throw ProtocolException("$: unexpected ${value.javaClass.name}")
}

/** Formats a number as JavaScript `String(number)` does: `1.0` → `1`, `1e21` → `1e+21`, `-0` → `0`. */
fun jsNumber(value: Double): String {
  if (value == 0.0) return "0"
  // Since JDK 19, Double.toString gives the shortest digits that read back as the same double, as JavaScript does.
  val decimal = BigDecimal(abs(value).toString()).stripTrailingZeros()
  val digits = decimal.unscaledValue().toString()
  val k = digits.length
  val n = k - decimal.scale()
  val text = when {
    n in k..21 -> digits + "0".repeat(n - k)
    n in 1..21 -> digits.substring(0, n) + "." + digits.substring(n)
    n in -5..0 -> "0." + "0".repeat(-n) + digits
    else -> {
      val exponent = n - 1
      val mantissa = if (k == 1) digits else "${digits[0]}.${digits.substring(1)}"
      mantissa + "e" + (if (exponent > 0) "+" else "") + exponent
    }
  }
  return if (value < 0) "-$text" else text
}

private const val MAX_SAFE_INTEGER = 9007199254740991L

private typealias Field<T> = (JsonElement, String) -> T

private fun fail(path: String, problem: String): Nothing = throw ProtocolException("$path: $problem")

private class Fields(private val json: JsonObject, private val path: String) {
  private val known = mutableSetOf<String>()

  fun <T> required(key: String, field: Field<T>): T {
    known += key
    return field(json.get(key) ?: fail("$path.$key", "missing"), "$path.$key")
  }

  fun <T : Any> optional(key: String, field: Field<T>): T? {
    known += key
    return json.get(key)?.let { field(it, "$path.$key") }
  }

  fun refuseExtraKeys() {
    json.keySet().firstOrNull { it !in known }?.let { fail("$path.$it", "unexpected key") }
  }
}

private fun <T> read(value: Any?, strict: Boolean, build: Fields.() -> T): T = read(toJsonElement(value), "$", strict, build)

private fun <T> read(element: JsonElement, path: String, strict: Boolean, build: Fields.() -> T): T {
  if (element !is JsonObject) fail(path, "expected object")
  val fields = Fields(element, path)
  return fields.build().also { if (strict) fields.refuseExtraKeys() }
}

private fun primitive(element: JsonElement, path: String, expected: String, test: (JsonPrimitive) -> Boolean): JsonPrimitive =
  (element as? JsonPrimitive)?.takeIf(test) ?: fail(path, "expected $expected")

private fun string(element: JsonElement, path: String): String = primitive(element, path, "string") { it.isString }.asString

private fun nonEmpty(element: JsonElement, path: String): String =
  string(element, path).ifEmpty { fail(path, "expected a non-empty string") }

private fun number(element: JsonElement, path: String): Double = primitive(element, path, "number") { it.isNumber }.asDouble

private fun boolean(element: JsonElement, path: String): Boolean = primitive(element, path, "boolean") { it.isBoolean }.asBoolean

private fun integer(element: JsonElement, path: String, min: Long, max: Long): Long {
  val value = (element as? JsonPrimitive)?.takeIf { it.isNumber }?.asDouble
  if (value == null || value % 1.0 != 0.0 || value < min || value > max) fail(path, "expected an integer in [$min, $max]")
  return value.toLong()
}

/** A strict reader knows every member; a lenient one accepts any string. */
private fun oneOf(strict: Boolean, vararg members: String): Field<String> = { element, path ->
  string(element, path).also { if (strict && it !in members) fail(path, "unknown value \"$it\"") }
}

private fun <T> list(element: JsonElement, path: String, item: Field<T>): List<T> {
  if (element !is JsonArray) fail(path, "expected array")
  return element.mapIndexed { i, value -> item(value, "$path[$i]") }
}

private fun position(strict: Boolean): Field<Position> = { element, path ->
  read(element, path, strict) { Position(required("line", ::offset), required("character", ::offset)) }
}

private fun offset(element: JsonElement, path: String): Int = integer(element, path, 0, Int.MAX_VALUE.toLong()).toInt()

private fun range(strict: Boolean): Field<Range> = { element, path ->
  read(element, path, strict) { Range(required("start", position(strict)), required("end", position(strict))) }
}

private fun listingFailure(element: JsonElement, path: String, strict: Boolean): ListingFailure =
  read(element, path, strict) { ListingFailure(required("message", ::string)) }
