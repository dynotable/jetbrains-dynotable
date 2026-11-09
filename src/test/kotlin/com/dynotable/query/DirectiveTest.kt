package com.dynotable.query

import com.dynotable.protocol.IndexRow
import com.dynotable.protocol.ListIndicesResult
import com.dynotable.protocol.ListingFailure
import com.dynotable.protocol.UnreachableTarget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.api.Test

/** The `// dynotable:` line that Pick Index and New Query write. */
class DirectiveTest {
  @Test
  fun `inserts a directive when line 0 is not one`() {
    assertEquals("// dynotable: index=orders\n{\"match_all\": {}}", applyPin("{\"match_all\": {}}", Pin("orders")))
  }

  @Test
  fun `replaces index=`() {
    assertEquals("// dynotable: index=orders", applyPin("// dynotable: index=old", Pin("orders")))
  }

  // The server refuses a key set twice to different values, so a kept duplicate blocks every run.
  @Test
  fun `collapses a duplicated key`() {
    assertEquals("// dynotable: index=orders", applyPin("// dynotable: index=a index=b", Pin("orders")))
  }

  @Test
  fun `keeps unknown tokens and leading whitespace`() {
    assertEquals("  //dynotable: mode=x index=orders", applyPin("  //dynotable: mode=x index=a", Pin("orders")))
  }

  // A pin left from an earlier pick would send a run to a target that does not host the new index.
  @Test
  fun `drops a target= pin the new pick does not need`() {
    assertEquals("// dynotable: index=b", applyPin("// dynotable: index=a target=x", Pin("b")))
  }

  private val complete = ListIndicesResult(
    indices = listOf(IndexRow("shared", "prod-search"), IndexRow("shared", "analytics node"), IndexRow("solo", "prod-search")),
    unreachable = emptyList(),
    indexListingFailed = null,
    partial = null,
  )

  // An unpinned name on two targets is refused as ambiguous.
  @Test
  fun `adds target= for a name on two targets, and not for a unique one`() {
    assertEquals(Pin("shared", "prod-search"), pinFor(IndexRow("shared", "prod-search"), complete))
    assertEquals(Pin("solo"), pinFor(IndexRow("solo", "prod-search"), complete))
  }

  // A target that did not list its indices can host the name too, so the server refuses an
  // unpinned query on it although the picked target is healthy.
  @ParameterizedTest
  @MethodSource("incomplete")
  fun `adds target= for a unique name when the listing is incomplete`(listing: ListIndicesResult) {
    assertEquals(Pin("solo", "prod-search"), pinFor(IndexRow("solo", "prod-search"), listing))
  }

  // Tokens are whitespace-delimited: `target=analytics node` would pin "analytics" and add junk.
  @Test
  fun `refuses a target name with a space`() {
    assertEquals(
      Unpinnable("\"shared\" is on more than one OpenSearch target, and the target name \"analytics node\" contains a space, so it cannot be pinned."),
      pinFor(IndexRow("shared", "analytics node"), complete),
    )
    val incomplete = ListIndicesResult(listOf(IndexRow("solo", "search 1")), listOf(UnreachableTarget("east", "No answer.")), null, null)
    assertEquals(
      Unpinnable("\"solo\" can also be on a target that did not list its indices, and the target name \"search 1\" contains a space, so it cannot be pinned."),
      pinFor(IndexRow("solo", "search 1"), incomplete),
    )
  }

  companion object {
    private val base = ListIndicesResult(listOf(IndexRow("solo", "prod-search")), emptyList(), null, null)

    @JvmStatic
    fun incomplete() = listOf(
      base.copy(unreachable = listOf(UnreachableTarget("east", "No answer."))),
      base.copy(partial = ListingFailure("Discovery failed.")),
      base.copy(indexListingFailed = ListingFailure("Indices failed.")),
    )
  }
}
