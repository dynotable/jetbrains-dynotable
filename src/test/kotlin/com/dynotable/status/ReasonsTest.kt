package com.dynotable.status

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

// The status widget, the notification and the explorer message all read this table. An empty
// sentence leaves a bare button.
class ReasonsTest {
  // STOPPED has no other fix: the user stopped the server on purpose.
  @ParameterizedTest
  @EnumSource(Reason::class, names = ["STOPPED"], mode = EnumSource.Mode.EXCLUDE)
  fun `each reason has a sentence and a fix first`(reason: Reason) {
    assertTrue(reason.sentence.isNotBlank())
    assertNotEquals(Action.RECONNECT, reason.actions.first())
  }

  // A fix in the app, such as a licence activation, writes no new port file: only Reconnect clears the latch.
  @Test
  fun `each latched reason offers Reconnect`() {
    assertEquals(emptyList<Reason>(), Reason.entries.filter { it.latched && Action.RECONNECT !in it.actions })
  }
}
