package com.dynotable.results

import com.dynotable.protocol.RunResult
import com.dynotable.protocol.jsNumber
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/** The rows of a result as the tool window shows them: one text and one kind per column, per row. */
data class ResultView(
  val summary: String,
  val columns: List<String>,
  val cells: List<List<String>>,
  val kinds: List<List<CellKind>>,
  val values: List<JsonObject>,
)

/** How a cell draws and sorts. A tagged value such as `{"N": …}` is a [STRUCTURE]: its text is JSON. */
enum class CellKind { TEXT, NUMBER, BOOLEAN, NULL, MISSING, STRUCTURE }

sealed interface View {
  data object Empty : View

  data class Running(val runId: String) : View

  data object Cancelled : View

  data class Result(val run: RunResult) : View {
    val shown: ResultView = resultView(run)
  }
}

/** `totalRows` null means unknown: the returned count never stands in for it. */
fun summary(result: RunResult): String {
  val returned = jsNumber(result.returnedRows)
  val cap = jsNumber(result.effectiveRowCap)
  val parts = mutableListOf(
    result.totalRows?.let { "$returned of ${jsNumber(it)} rows" } ?: "$returned ${if (result.returnedRows == 1.0) "row" else "rows"}",
  )
  if (result.truncated) parts += if (result.totalRows == null) "limited to $cap rows, total unknown" else "limited to $cap rows"
  result.itemsScanned?.let { parts += "${jsNumber(it)} items scanned" }
  parts += "${jsNumber(result.elapsedMs)} ms"
  return parts.joinToString(" · ")
}

/**
 * A column is a literal key, never a path: `o.orderId` is one key. A string shows as it is. Any other
 * value, a tagged one such as `{"N": …}` or `{"SS": […]}` too, shows as JSON.
 */
fun cellText(row: JsonObject, column: String): String {
  val value = row.get(column) ?: return ""
  return if (value is JsonPrimitive && value.isString) value.asString else json(value)
}

fun cellKind(row: JsonObject, column: String): CellKind = when (val value = row.get(column)) {
  null -> CellKind.MISSING
  is JsonNull -> CellKind.NULL
  is JsonPrimitive -> when {
    value.isNumber -> CellKind.NUMBER
    value.isBoolean -> CellKind.BOOLEAN
    else -> CellKind.TEXT
  }
  else -> CellKind.STRUCTURE
}

fun resultView(result: RunResult) = ResultView(
  summary = summary(result),
  columns = result.columns,
  cells = result.rows.map { row -> result.columns.map { cellText(row, it) } },
  kinds = result.rows.map { row -> result.columns.map { cellKind(row, it) } },
  values = result.rows,
)

/** A missing value or `null`: it sorts last, counts as no value in the totals, and filters as `(empty)`. */
fun CellKind.isEmpty() = this == CellKind.MISSING || this == CellKind.NULL

/** A column sorts by number when every cell that has a value is a number. */
fun isNumericColumn(kinds: List<CellKind>): Boolean = kinds.filterNot { it.isEmpty() }.let { it.isNotEmpty() && it.all { k -> k == CellKind.NUMBER } }

/** Two non-empty cells: by value in a numeric column (`9` before `10`), else by text, in any case. */
fun compareCellTexts(a: String, b: String, numeric: Boolean): Int =
  if (numeric) a.toBigDecimal().compareTo(b.toBigDecimal()) else a.compareTo(b, ignoreCase = true)

/** A cell matches a filter when its text holds [filter], in any case. A blank filter matches all. */
fun matchesFilter(cells: List<String>, filter: String): Boolean =
  filter.isBlank() || cells.any { it.contains(filter.trim(), ignoreCase = true) }

/** The [rows] × [columns] (model indices, in view order) of [view]. */
data class Selection(val rows: List<Int>, val columns: List<Int>)

fun selectionCsv(view: ResultView, selection: Selection, header: Boolean): String = delimited(view, selection, header, ',')

/**
 * What Cmd/Ctrl+C copies for two or more cells: tab-separated, quoted as CSV quotes, but with no
 * formula guard, as the IDE's data grids copy. One cell copies its value as it is.
 */
fun selectionTsv(view: ResultView, selection: Selection, header: Boolean): String = delimited(view, selection, header, '\t', guard = false)

private fun delimited(view: ResultView, selection: Selection, header: Boolean, delimiter: Char, guard: Boolean = true): String =
  delimitedLines((if (header) listOf(selection.columns.map { view.columns[it] }) else emptyList()) + selection.rows.map { r -> selection.columns.map { view.cells[r][it] } }, delimiter, guard)

/** Copy Column: the values of the given rows, one per line. Plain text, not CSV: no header, no guard. */
fun columnText(view: ResultView, rows: List<Int>, column: Int): String = rows.joinToString("\n") { view.cells[it][column] }

/**
 * The status line for the selected cells of one column, or null when it has fewer than 2 cells or no
 * number. Count is the cells with a value; Sum, Avg, Min and Max read the numbers only, so a tagged
 * exact `{"N": …}` (JSON text) is left out rather than rounded.
 */
fun totals(cells: List<Pair<String, CellKind>>): String? {
  if (cells.size < 2) return null
  val numbers = cells.filter { it.second == CellKind.NUMBER }.map { it.first.toBigDecimal() }
  if (numbers.isEmpty()) return null
  val sum = numbers.fold(java.math.BigDecimal.ZERO, java.math.BigDecimal::add)
  val count = cells.count { !it.second.isEmpty() }
  return "Count $count · Sum ${jsNumber(sum.toDouble())} · Avg ${jsNumber(sum.toDouble() / numbers.size)} · " +
    "Min ${jsNumber(numbers.min().toDouble())} · Max ${jsNumber(numbers.max().toDouble())}"
}

/**
 * The distinct values of a column for its filter list, in sort order. A `null` entry is `(empty)`: it
 * stands for a missing value and `null` alike, and comes first.
 */
fun distinctValues(view: ResultView, column: Int): List<String?> {
  val kinds = view.kinds.map { it[column] }
  val numeric = isNumericColumn(kinds)
  val values = view.cells.indices.filterNot { kinds[it].isEmpty() }.map { view.cells[it][column] }.distinct()
    .sortedWith { a, b -> compareCellTexts(a, b, numeric) }
  return (if (kinds.any { it.isEmpty() }) listOf(null) else emptyList()) + values
}

/** A row passes when, in every filtered column, its value is one of the kept values. */
fun matchesColumnFilters(view: ResultView, row: Int, filters: Map<Int, Set<String?>>): Boolean =
  filters.all { (column, kept) -> (if (view.kinds[row][column].isEmpty()) null else view.cells[row][column]) in kept }

/** One object per row, with the selected keys in view order. A row that lacks a key leaves it out. */
fun selectionJson(view: ResultView, selection: Selection): String = json(
  JsonArray().apply {
    selection.rows.forEach { r ->
      add(JsonObject().apply { selection.columns.map { view.columns[it] }.forEach { key -> view.values[r].get(key)?.let { add(key, it) } } })
    }
  },
  "  ",
)

/** A GitHub table: a `|` in a cell is escaped, and a line break becomes a space. */
fun selectionMarkdown(view: ResultView, selection: Selection): String {
  fun line(cells: List<String>) = cells.joinToString(" | ", "| ", " |") { it.replace("|", "\\|").replace(Regex("\r\n|\r|\n"), " ") }
  return (listOf(line(selection.columns.map { view.columns[it] }), line(selection.columns.map { "---" })) + selection.rows.map { r -> line(selection.columns.map { view.cells[r][it] }) })
    .joinToString("\n")
}

/**
 * What the value viewer shows: indented JSON for a nested value and for a string that holds a JSON
 * object or array; any other value as Copy Value gives it.
 */
fun viewerText(view: ResultView, row: Int, column: Int): String {
  val value = view.values[row].get(view.columns[column]) ?: return ""
  if (value is JsonPrimitive && value.isString) {
    val text = value.asString
    val parsed = text.trim().takeIf { it.startsWith("{") || it.startsWith("[") }?.let { runCatching { com.google.gson.JsonParser.parseString(it) }.getOrNull() }
    return if (parsed != null && (parsed.isJsonObject || parsed.isJsonArray)) json(parsed, "  ") else text
  }
  return if (value is JsonPrimitive || value is JsonNull) json(value) else json(value, "  ")
}

private val NUMBER = Regex("""-?\d+(\.\d+)?([eE][+-]?\d+)?""")
private const val FORMULA_START = "=+-@\t\r\n"

/** A spreadsheet runs a cell that starts with a formula character, unless it is a plain number. */
private fun delimitedField(text: String, delimiter: Char, guard: Boolean): String {
  val guarded = if (guard && text.isNotEmpty() && text[0] in FORMULA_START && !NUMBER.matches(text)) "'$text" else text
  return if (guarded.any { it == '"' || it == delimiter || it == '\r' || it == '\n' }) "\"${guarded.replace("\"", "\"\"")}\"" else guarded
}

private fun delimitedLines(lines: List<List<String>>, delimiter: Char, guard: Boolean = true): String =
  lines.joinToString("\r\n") { line -> line.joinToString(delimiter.toString()) { delimitedField(it, delimiter, guard) } }

fun csv(lines: List<List<String>>): String = delimitedLines(lines, ',')

fun toCsv(result: RunResult): String = csv(listOf(result.columns) + resultView(result).cells)

fun toJson(result: RunResult): String = json(JsonArray().apply { result.rows.forEach(::add) }, "  ")

/**
 * As JavaScript `JSON.stringify(value, null, indent)`. Gson's writer escapes `<`, `=`, U+2028 and
 * U+2029 and prints `1.0` for a decoded `1`; the server's text must stay as it is.
 */
fun json(value: JsonElement, indent: String = ""): String = StringBuilder().also { write(it, value, indent, "") }.toString()

private fun write(out: StringBuilder, value: JsonElement, indent: String, at: String) {
  when (value) {
    is JsonPrimitive -> when {
      value.isString -> quote(out, value.asString)
      value.isBoolean -> out.append(value.asBoolean)
      else -> out.append(jsNumber(value.asDouble))
    }
    is JsonArray -> block(out, '[', ']', value.toList(), indent, at) { item, inner -> write(out, item, indent, inner) }
    is JsonObject -> block(out, '{', '}', value.entrySet().toList(), indent, at) { (key, item), inner ->
      quote(out, key)
      out.append(if (indent.isEmpty()) ":" else ": ")
      write(out, item, indent, inner)
    }
    else -> out.append("null")
  }
}

private fun <T> block(out: StringBuilder, open: Char, close: Char, items: List<T>, indent: String, at: String, item: (T, String) -> Unit) {
  out.append(open)
  if (items.isNotEmpty()) {
    val inner = at + indent
    items.forEachIndexed { i, it ->
      if (i > 0) out.append(',')
      if (indent.isNotEmpty()) out.append('\n').append(inner)
      item(it, inner)
    }
    if (indent.isNotEmpty()) out.append('\n').append(at)
  }
  out.append(close)
}

private fun quote(out: StringBuilder, text: String) {
  out.append('"')
  for (c in text) {
    when (c) {
      '"' -> out.append("\\\"")
      '\\' -> out.append("\\\\")
      '\b' -> out.append("\\b")
      '\u000C' -> out.append("\\f")
      '\n' -> out.append("\\n")
      '\r' -> out.append("\\r")
      '\t' -> out.append("\\t")
      else -> if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
    }
  }
  out.append('"')
}
