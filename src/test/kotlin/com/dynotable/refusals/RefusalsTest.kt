package com.dynotable.refusals

import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class RefusalsTest {
  // As lsp4j decodes an error answer: `data` is a JsonElement.
  private fun refused(code: String) = Failure.Error(ResponseError(-32803, "No.", JsonObject().apply { addProperty("code", code) }))

  @ParameterizedTest
  @CsvSource(
    "profile-required, SELECT_PROFILE",
    "signed-out, OPEN_APP",
    "profile-window-required, OPEN_APP",
    "reauth-required, OPEN_APP",
  )
  fun `each code offers its action and keeps the server's sentence`(code: String, action: RefusalAction) {
    assertEquals(Refusal.Message("No.", listOf(action)), refusalOf(refused(code), false, OPEN_IN_APP))
  }

  // A newer server's code must not get another code's action.
  @Test
  fun `an unknown code keeps the sentence and offers no action`() {
    assertEquals(Refusal.Message("No.", emptyList()), refusalOf(refused("quota-spent"), false, RUN_STATEMENT))
  }

  // A refusal with no readable code must still show the server's sentence, not throw.
  @Test
  fun `a -32803 with no data or with data that is not an object keeps the sentence`() {
    for (data in listOf(null, JsonPrimitive("profile-required"))) {
      assertEquals(Refusal.Message("No.", emptyList()), refusalOf(Failure.Error(ResponseError(-32803, "No.", data)), false, RUN_STATEMENT))
    }
  }

  // Stage sends openInApp. If its refusal offered Stage too, each click would show the refusal again.
  @Test
  fun `stage-in-app offers Stage for a run only`() {
    assertEquals(Refusal.Message("No.", listOf(RefusalAction.STAGE)), refusalOf(refused("stage-in-app"), false, RUN_STATEMENT))
    assertEquals(Refusal.Message("No.", emptyList()), refusalOf(refused("stage-in-app"), false, OPEN_IN_APP))
  }

  // The user asked for the cancel; the server's refusal of the run is not news.
  @Test
  fun `a run this client cancelled is silent`() {
    assertEquals(Refusal.Silent, refusalOf(refused("run-failed"), true, RUN_STATEMENT))
  }

  // The latched reason already announced the end of the session.
  @Test
  fun `a request that the ended session dropped is silent`() {
    assertEquals(Refusal.Silent, refusalOf(Failure.SessionEnded, false, RUN_STATEMENT))
  }

  @Test
  fun `a request on a client that is not running shows the connection status`() {
    assertEquals(Refusal.ShowStatus, refusalOf(Failure.NotRunning, false, RUN_STATEMENT))
  }
}
