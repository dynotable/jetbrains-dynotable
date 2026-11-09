package com.dynotable.files

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SqlGlobsTest {
  // A glob with the Java PathMatcher rules needs a "/" after "**", so a file at the root never attaches.
  @Test
  fun `a root-level file matches a pattern that starts with a double star segment`() {
    assertTrue(SqlGlobs.matches(listOf("**/*.sql"), "/w/p", "/w/p/q.sql"))
  }

  // A project's patterns must not switch a file of another folder, also not one whose path only starts with the same text.
  @Test
  fun `a file outside the base dir does not match`() {
    assertFalse(SqlGlobs.matches(listOf("**/*.sql"), "/w/p", "/w/other/q.sql"))
    assertFalse(SqlGlobs.matches(listOf("**/*.sql"), "/w/p", "/w/p2/q.sql"))
  }

  // In VS Code a single star stays in one segment: "*.sql" means the root only.
  @Test
  fun `a single star does not cross a slash`() {
    assertFalse(SqlGlobs.matches(listOf("*.sql"), "/w/p", "/w/p/db/q.sql"))
  }

  @Test
  fun `a brace group matches each of its alternatives`() {
    assertTrue(SqlGlobs.matches(listOf("{db,etc}/*.sql"), "/w/p", "/w/p/etc/q.sql"))
  }
}
