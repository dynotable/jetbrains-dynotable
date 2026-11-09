package com.dynotable.explorer

import com.dynotable.protocol.IndexRow
import com.dynotable.protocol.ListIndicesResult
import com.dynotable.protocol.ListTablesResult
import com.dynotable.protocol.ListingFailure
import com.dynotable.protocol.UnreachableTarget
import com.dynotable.query.Dialect
import com.dynotable.query.Pin
import com.dynotable.query.Query
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The explorer tree and New Query on its rows. */
class ExplorerModelTest {
  private val noTables = Listing.Ok(ListTablesResult(emptyList(), null))

  private fun indices(
    rows: List<IndexRow> = emptyList(),
    unreachable: List<UnreachableTarget> = emptyList(),
    indexListingFailed: ListingFailure? = null,
    partial: ListingFailure? = null,
  ) = Listing.Ok(ListIndicesResult(rows, unreachable, indexListingFailed, partial))

  // Without a `target=` pin, the server cannot tell which of the two indices a query means.
  @Test
  fun `gives a name on two targets one node per target, each pinned to its target`() {
    val tree = treeOf(noTables, indices(listOf(IndexRow("shared", "east"), IndexRow("shared", "west"))))
    assertEquals(
      listOf(
        Node.Group(
          "OpenSearch",
          listOf(
            Node.Target("east", listOf(Node.Index("shared", Pin("shared", "east")))),
            Node.Target("west", listOf(Node.Index("shared", Pin("shared", "west")))),
          ),
        ),
      ),
      tree,
    )
  }

  // A listing that failed in part must not look complete.
  @Test
  fun `shows each failure field as a message node`() {
    val tree = treeOf(
      Listing.Ok(ListTablesResult(listOf("orders"), ListingFailure("Tables failed."))),
      indices(
        unreachable = listOf(UnreachableTarget("east", "No answer.")),
        indexListingFailed = ListingFailure("Indices failed."),
        partial = ListingFailure("Discovery failed."),
      ),
    )
    assertEquals(
      listOf(
        Node.Group("DynamoDB", listOf(Node.Message("Tables failed."), Node.Table("orders"))),
        Node.Group("OpenSearch", listOf(Node.Message("Indices failed."), Node.Message("Discovery failed."), Node.Message("east: No answer."))),
      ),
      tree,
    )
  }

  // A refused request still tells the user why the engine shows nothing.
  @Test
  fun `shows a refused request as a message node in its group`() {
    assertEquals(
      listOf(Node.Group("DynamoDB", listOf(Node.Message("Sign in again.")))),
      treeOf(Listing.Failed("Sign in again."), indices()),
    )
  }

  // A profile with no OpenSearch engine answers an empty list; an empty group is noise.
  @Test
  fun `shows no group for an engine with an empty list and no failure`() {
    assertEquals(emptyList<Node>(), treeOf(noTables, indices()))
  }

  // An unescaped quote ends the identifier early and the statement breaks.
  @Test
  fun `New Query doubles a quote in a table name`() {
    assertEquals(Query(Dialect.SQL, "SELECT * FROM \"say \"\"hi\"\"\""), queryOf(Node.Table("say \"hi\"")))
  }
}
