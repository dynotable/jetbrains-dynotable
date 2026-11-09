package com.dynotable.query

import com.dynotable.protocol.IndexRow
import com.dynotable.protocol.ListIndicesResult

/** What line 0 of an OpenSearch query names: an index, and a target when [pinFor] needs one. */
sealed interface Pinning

data class Pin(val index: String, val target: String? = null) : Pinning

/** [warning]: why the index cannot be pinned. Nothing opens and nothing is written. */
data class Unpinnable(val warning: String) : Pinning, Opening

/** What New Query opens: a [Query], or an [Unpinnable] warning. */
sealed interface Opening

/** [suffix]: a file name ending that gives the file its DynoTable file type. */
enum class Dialect(val label: String, val suffix: String) {
  SQL("SQL", ".dynotable.sql"),
  PARTIQL("PartiQL", ".partiql"),
  OPENSEARCH("OpenSearch", ".opensearch"),
}

/** A new query's dialect and text. */
data class Query(val dialect: Dialect, val text: String) : Opening

private val DIRECTIVE = Regex("([ \t]*//[ \t]*dynotable:)(.*)")
private val KEYS = listOf("index", "target")

/**
 * A `target=` pin is needed when more than one target lists the name, or when the listing is
 * incomplete: a target that did not list its indices can also host the name. Directive tokens are
 * whitespace-delimited, so a target name with whitespace cannot be pinned.
 */
fun pinFor(pick: IndexRow, listing: ListIndicesResult): Pinning {
  val repeated = listing.indices.count { it.name == pick.name } > 1
  val incomplete = listing.unreachable.isNotEmpty() || listing.partial != null || listing.indexListingFailed != null
  if (!repeated && !incomplete) return Pin(pick.name)
  if (pick.target.any(Char::isWhitespace)) {
    val why = if (repeated) "is on more than one OpenSearch target" else "can also be on a target that did not list its indices"
    return Unpinnable("\"${pick.name}\" $why, and the target name \"${pick.target}\" contains a space, so it cannot be pinned.")
  }
  return Pin(pick.name, pick.target)
}

fun directiveLine(pin: Pin): String = "// dynotable: index=${pin.index}${pin.target?.let { " target=$it" }.orEmpty()}"

/**
 * The new text of line 0 ([line], without its line break). A directive keeps its prefix and its
 * unknown tokens; a key it sets twice collapses to one token. Other text gets a directive line above it.
 */
fun applyPin(line: String, pin: Pin): String {
  val match = DIRECTIVE.matchEntire(line) ?: return "${directiveLine(pin)}\n$line"
  val wanted = mapOf("index" to pin.index, "target" to pin.target)
  val written = mutableSetOf<String>()
  val tokens = mutableListOf<String>()
  for (token in match.groupValues[2].split(' ', '\t').filter(String::isNotEmpty)) {
    val key = token.substringBefore('=', missingDelimiterValue = "")
    when {
      key !in KEYS -> tokens += token
      written.add(key) -> wanted[key]?.let { tokens += "$key=$it" }
    }
  }
  for (key in KEYS) if (key !in written) wanted[key]?.let { tokens += "$key=$it" }
  return "${match.groupValues[1]} ${tokens.joinToString(" ")}"
}
