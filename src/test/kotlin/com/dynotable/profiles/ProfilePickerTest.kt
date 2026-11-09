package com.dynotable.profiles

import com.dynotable.editor.ServerCommands
import com.dynotable.harness.PopupRow
import com.dynotable.harness.harnessFixture
import com.dynotable.harness.waitFor
import com.dynotable.refusals.RUN_STATEMENT
import com.dynotable.settings.DynoTableWorkspaceSettings
import com.dynotable.status.Action
import com.dynotable.status.Reason
import com.dynotable.status.Status
import com.dynotable.ui.StatusWidget
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.EDT
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import kotlin.time.Duration.Companion.minutes

/** The profile picker and the requested profile, against the fake server. */
@TestApplication
@DisabledOnOs(OS.WINDOWS)
class ProfilePickerTest {
  private val tempDir = tempPathFixture()
  private val project = projectFixture(tempDir, openAfterCreation = true)
  private val module = project.moduleFixture(tempDir, addPathToSourceRoot = true)
  private val fixture by codeInsightFixture(project, tempDir)
  private val h by harnessFixture(project)

  private val stored get() = DynoTableWorkspaceSettings.getInstance(project.get())
  private val picker get() = ProfilePicker.getInstance(project.get())

  private fun run(test: suspend () -> Unit) = timeoutRunBlocking(2.minutes) { test() }

  private suspend fun open(name: String = "q.dynotable.sql"): VirtualFile =
    withContext(Dispatchers.EDT) { fixture.configureByText(name, "SELECT * FROM \"orders\"").virtualFile }

  private suspend fun connected(): Int {
    waitFor("connected, now ${h.core.status}") { h.core.status is Status.Connected }
    return (h.core.status as Status.Connected).attempt
  }

  /** The profile the latest platform session asked for. The preflight always sends none. */
  private fun requested(): String? =
    h.fake.initializeParams.last().asJsonObject["initializationOptions"].asJsonObject["profile"]?.asString

  @Test
  fun `the first connection sends the stored id`() = run {
    stored.profile = STORED
    h.fake.start()
    open()
    connected()
    assertEquals(STORED.id, requested())
  }

  // The server does not confirm the binding, so the status bar must not claim one. A profile name is the user's text.
  @Test
  fun `choosing a profile connects anew with its id, and the status bar shows it as requested`() = run {
    val roster = h.fake.fixture("list-profiles.json").asJsonArray
    roster[1].asJsonObject.addProperty("name", "<b>p2</b>")
    h.fake.handle("dynotable/listProfiles") { _, _ -> roster }
    h.fake.start()
    open()
    val before = connected()
    val sessions = h.fake.platformSessions()
    h.popupAnswer = "<b>p2</b>"
    picker.select()
    assertEquals(before + 1, connected())
    assertEquals(sessions + 1, h.fake.platformSessions())
    assertEquals("profile-2-id", requested())
    assertEquals(RequestedProfile("profile-2-id", "<b>p2</b>"), stored.profile)
    val widget = StatusWidget(project.get())
    assertEquals("DynoTable: <b>p2</b>", widget.getText())
    assertEquals("Connected to DynoTable. Requested profile: &lt;b&gt;p2&lt;/b&gt;.", widget.getTooltipText())
  }

  // The server would refuse the profile at initialize; the session must not end for that.
  @Test
  fun `an incomplete profile shows its reason and cannot be chosen`() = run {
    h.fake.handle("dynotable/listProfiles") { _, _ -> h.fake.fixture("list-profiles-incomplete.json") }
    h.fake.start()
    open()
    connected()
    val sessions = h.fake.platformSessions()
    h.popupAnswer = "profile-3-name"
    picker.select()
    delay(500)
    val reason = "This profile is incomplete. Finish setting it up in DynoTable."
    assertEquals(listOf(PopupRow("profile-3-name", false, "profile-3-slug — DynamoDB — $reason")), h.popups.single())
    assertEquals(sessions, h.fake.platformSessions())
    assertNull(stored.profile)
  }

  // The roster came from the replaced session, maybe from another app.
  @Test
  fun `a choice made while the session changes is not stored`() = run {
    stored.profile = STORED
    h.fake.start()
    open()
    val attempt = connected()
    val sessions = h.fake.platformSessions()
    h.popupAnswer = CHOSEN.name
    h.duringPopup = {
      h.core.perform(Action.RECONNECT)
      runBlocking { waitFor("the session to change") { h.core.status != Status.Connected(attempt) } }
    }
    picker.select()
    connected()
    delay(500)
    assertEquals(STORED, stored.profile)
    // One for the reconnect only.
    assertEquals(sessions + 1, h.fake.platformSessions())
    assertEquals(STORED.id, requested())
  }

  @Test
  fun `an empty roster offers Open DynoTable`() = run {
    h.fake.handle("dynotable/listProfiles") { _, _ -> JsonArray() }
    h.fake.start()
    open()
    connected()
    picker.select()
    val sentence = "DynoTable has no profiles. Create a profile in DynoTable."
    assertEquals(listOf(sentence), h.sentences())
    h.click(h.notifications.single(), Action.OPEN_APP.label)
    // No app is installed in the harness, so Open DynoTable answers with that reason.
    waitFor("the Open DynoTable answer") { h.notifications.size == 2 }
    assertEquals(listOf(sentence, Reason.NOT_INSTALLED.sentence), h.sentences())
  }

  @Test
  fun `an unbound Run lens click opens the picker`() = run {
    val refusal = h.fake.fixture("error-profile-required.json").asJsonObject["message"].asString
    h.fake.handle("dynotable/listTables") { _, _ -> throw h.fake.refusal("error-profile-required.json") }
    h.fake.handle("command:$RUN_STATEMENT") { _, _ -> throw h.fake.refusal("error-profile-required.json") }
    h.fake.start()
    val file = open("lens.dynotable.sql")
    waitFor("unbound, now ${h.core.status}") { h.core.status is Status.Unbound }
    // What a click on the server's Run lens sends.
    val args = JsonParser.parseString("""{"uri":"${file.url}","range":{"start":{"line":0,"character":0},"end":{"line":0,"character":22}}}""")
    ServerCommands.getInstance(project.get()).send(file, RUN_STATEMENT, listOf(args as JsonObject))
    waitFor("the refusal") { h.notifications.isNotEmpty() }
    assertEquals(listOf(StringUtil.escapeXmlEntities(refusal)), h.sentences())
    h.click(h.notifications.single(), Action.SELECT_PROFILE.label)
    waitFor("the profile picker") { h.popups.size == 1 }
    assertEquals(listOf("profile-1-name", "profile-2-name"), h.popups.single().map { it.text })
  }

  private companion object {
    val STORED = RequestedProfile("profile-1-id", "profile-1-name")
    val CHOSEN = RequestedProfile("profile-2-id", "profile-2-name")
  }
}
