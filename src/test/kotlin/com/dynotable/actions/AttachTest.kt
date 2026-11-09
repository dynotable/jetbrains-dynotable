package com.dynotable.actions

import com.dynotable.connect.Platform
import com.dynotable.files.SqlGlobs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class AttachTest {
  private val existing = listOf("**/*.dynotable.sql")

  // Unescaped, `[prod]` is a character class and `{a}` a brace group: neither matches the file, and each
  // matches a near name. The settings page trims a pattern, so a padded name would attach `report.sql`.
  @ParameterizedTest
  @CsvSource(
    delimiter = '|',
    ignoreLeadingAndTrailingWhitespace = false,
    value = [
      "[prod].sql|[[]prod[]].sql|p.sql",
      "{a}.sql|[{]a[}].sql|a.sql",
      "a*?.sql|a[*][?].sql|ab.sql",
      " report.sql |[ ]report.sql[ ]|report.sql",
    ],
  )
  fun `escapes a name so that it matches only itself`(name: String, pattern: String, near: String) {
    assertEquals(existing + pattern, attach(Platform.LINUX, "/w", "/w/$name", existing))
    assertTrue(SqlGlobs.matches(listOf(pattern), "/w", "/w/$name"))
    assertFalse(SqlGlobs.matches(listOf(pattern), "/w", "/w/$near"))
  }

  // A backslash in the glob is not a separator, so the pattern would never match.
  @Test
  fun `turns Windows backslashes into slashes`() {
    assertEquals(existing + "db/q.sql", attach(Platform.WINDOWS, "C:\\w", "C:\\w\\db\\q.sql", existing))
  }

  @Test
  fun `refuses with no project base dir`() {
    assertNull(attach(Platform.LINUX, null, "/w/q.sql", existing))
  }

  // A content root can lie outside the base dir, also in a sibling folder whose name starts the same.
  // Patterns are relative to the base dir, so no pattern can attach that file.
  @Test
  fun `refuses a file outside the base dir`() {
    assertNull(attach(Platform.LINUX, "/w/p", "/w/p2/q.sql", existing))
  }
}
