package com.dynotable.files

import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

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

  // A shared project file can hold any text; a throw here would break the file type of every .sql file.
  @Test
  fun `an invalid class matches nothing, and the other patterns still apply`() {
    assertFalse(SqlGlobs.matches(listOf("[z-a].sql"), "/w/p", "/w/p/b.sql"))
    assertTrue(SqlGlobs.matches(listOf("[z-a].sql", "*.sql"), "/w/p", "/w/p/b.sql"))
  }

  // A cloned repo sets the patterns and the file type overrider can run on the EDT. A backtracking
  // match of many stars against a name that almost matches takes exponential time and freezes the IDE.
  @Test
  fun `a pattern with many stars and a name that almost matches return at once`() {
    val pattern = "*a".repeat(12) + "*b.sql"
    val name = "a".repeat(60) + ".sql"
    assertTimeoutPreemptively(Duration.ofSeconds(2)) {
      assertFalse(SqlGlobs.matches(listOf(pattern), "/w", "/w/$name"))
    }
  }

  // One row per rule of the matcher that no other test covers.
  @ParameterizedTest
  @CsvSource(
    delimiter = '|',
    value = [
      "db/**|db/a/b/q.sql|true",
      "db/**/q.sql|db/q.sql|true",
      "db/**/q.sql|db/a/b/q.sql|true",
      "db/**/q.sql|dbx/q.sql|false",
      "a?q.sql|a/q.sql|false",
      "[a-c].sql|b.sql|true",
      "[a-c].sql|d.sql|false",
      "a[!x]q.sql|a/q.sql|false",
      "a[^x]q.sql|abq.sql|true",
      "a[]]b.sql|a]b.sql|true",
      "q{,.dynotable}.sql|q.sql|true",
      "[q.sql|[q.sql|true",
      "{q.sql|{q.sql|true",
    ],
  )
  fun `applies the VS Code glob rules`(pattern: String, path: String, expected: Boolean) {
    assertEquals(expected, SqlGlobs.matches(listOf(pattern), "/w", "/w/$path"))
  }
}
