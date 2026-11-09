package com.dynotable.explorer

import com.dynotable.protocol.ListIndicesResult
import com.dynotable.protocol.ListTablesResult
import com.dynotable.protocol.ProtocolException
import com.dynotable.query.Dialect
import com.dynotable.query.Opening
import com.dynotable.query.Pin
import com.dynotable.query.Pinning
import com.dynotable.query.Query
import com.dynotable.query.Unpinnable
import com.dynotable.query.directiveLine
import com.dynotable.query.pinFor
import com.dynotable.refusals.UNREADABLE

/** One listing request's answer. A refusal keeps the server's sentence. */
sealed interface Listing<out T> {
  data class Ok<T>(val value: T) : Listing<T>

  data class Failed(val message: String) : Listing<Nothing>
}

/** [parse] the answer, or a [Listing.Failed] that says why it cannot be read. */
fun <T> listingOf(answer: Any?, parse: (Any?) -> T): Listing<T> = try {
  Listing.Ok(parse(answer))
} catch (_: ProtocolException) {
  Listing.Failed(UNREADABLE)
}

sealed interface Node {
  data class Group(val label: String, val children: List<Node>) : Node

  data class Target(val label: String, val children: List<Node>) : Node

  data class Table(val name: String) : Node

  /** [pin]: what New Query writes into the directive, or why the index cannot be pinned. */
  data class Index(val name: String, val pin: Pinning) : Node

  data class Message(val text: String) : Node
}

/** The row text. Server text: show it as plain text. */
val Node.label: String
  get() = when (this) {
    is Node.Group -> label
    is Node.Target -> label
    is Node.Table -> name
    is Node.Index -> name
    is Node.Message -> text
  }

/**
 * DynamoDB → tables, OpenSearch → target → indices. An engine with nothing to show gets no group,
 * and neither does a listing that has not arrived (`null`).
 */
fun treeOf(tables: Listing<ListTablesResult>, indices: Listing<ListIndicesResult>?): List<Node> =
  listOf(Node.Group("DynamoDB", tableNodes(tables)), Node.Group("OpenSearch", indexNodes(indices))).filter { it.children.isNotEmpty() }

private fun tableNodes(listing: Listing<ListTablesResult>): List<Node> = when (listing) {
  is Listing.Failed -> listOf(Node.Message(listing.message))
  is Listing.Ok -> listOfNotNull(listing.value.tableListingFailed?.let { Node.Message(it.message) }) + listing.value.tables.map(Node::Table)
}

private fun indexNodes(listing: Listing<ListIndicesResult>?): List<Node> = when (listing) {
  null -> emptyList()
  is Listing.Failed -> listOf(Node.Message(listing.message))
  is Listing.Ok -> {
    val targets = listing.value.indices.groupBy { it.target }
      .map { (target, rows) -> Node.Target(target, rows.map { Node.Index(it.name, pinFor(it, listing.value)) }) }
    indexFailures(listing.value).map(Node::Message) + targets
  }
}

/** Why the index list can be incomplete, one sentence per failure. */
fun indexFailures(listing: ListIndicesResult): List<String> =
  listOfNotNull(listing.indexListingFailed?.message, listing.partial?.message) + listing.unreachable.map { "${it.target}: ${it.message}" }

/** New Query on a table or an index; `null` on other nodes. */
fun queryOf(node: Node): Opening? = when (node) {
  is Node.Table -> Query(Dialect.SQL, "SELECT * FROM \"${node.name.replace("\"", "\"\"")}\"")
  is Node.Index -> when (val pin = node.pin) {
    is Pin -> Query(Dialect.OPENSEARCH, "${directiveLine(pin)}\n")
    is Unpinnable -> pin
  }
  else -> null
}
