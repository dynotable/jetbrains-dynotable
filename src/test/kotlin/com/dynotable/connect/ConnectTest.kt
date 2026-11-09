package com.dynotable.connect

import com.dynotable.fake.FakeApp
import com.dynotable.fake.FakeRun
import com.dynotable.fake.FakeServer
import com.dynotable.harness.harnessFixture
import com.dynotable.harness.waitFor
import com.dynotable.settings.DynoTableAppSettings
import com.dynotable.settings.DynoTableProjectSettings
import com.dynotable.ui.StatusWidget
import com.dynotable.status.Action
import com.dynotable.status.Reason
import com.dynotable.status.Status
import com.google.gson.JsonObject
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.system.measureTimeMillis
import kotlin.time.Duration.Companion.minutes

/** The connection against the fake server: a real platform LSP client, the plugin's preflight and status. */
@TestApplication
@DisabledOnOs(OS.WINDOWS)
class ConnectTest {
  private val tempDir = tempPathFixture()
  private val project = projectFixture(tempDir, openAfterCreation = true)
  private val module = project.moduleFixture(tempDir, addPathToSourceRoot = true)
  private val fixture by codeInsightFixture(project, tempDir)
  private val h by harnessFixture(project)

  private suspend fun open(name: String, text: String = "SELECT * FROM t"): VirtualFile =
    withContext(Dispatchers.EDT) { fixture.configureByText(name, text).virtualFile }

  private suspend fun connected(): Int {
    waitFor("connected, now ${h.core.status}") { h.core.status is Status.Connected }
    return (h.core.status as Status.Connected).attempt
  }

  private fun liveSessions() = h.fake.sessions.count { it.initialized }

  private fun run(test: suspend () -> Unit) = timeoutRunBlocking(2.minutes) { test() }

  @Test
  fun `the preflight sends only the token, and the platform session gets the capabilities and the language id of each file type`() = run {
    h.fake.start()
    val files = mapOf(open("q.dynotable.sql") to "sql", open("q.partiql") to "partiql", open("q.opensearch", "{}") to "opensearch")
    connected()
    waitFor("didOpen of the three files") { files.keys.all { h.fake.opened(it.url) } }
    assertEquals(files.values.toList(), files.keys.map { h.fake.language(it.url) })
    // No profile: the server binds a profile at most once per connection.
    val preflight = h.fake.initializeParams.first().asJsonObject["initializationOptions"].asJsonObject
    assertEquals(JsonObject().apply { addProperty("token", h.fake.token) }, preflight)
    val capabilities = h.fake.initializeParams.last().asJsonObject["capabilities"].asJsonObject
    assertTrue(capabilities.getAsJsonObject("window").has("showMessage"))
    assertTrue(capabilities.getAsJsonObject("window")["workDoneProgress"].asBoolean)
    assertTrue(capabilities.getAsJsonObject("textDocument").getAsJsonObject("completion").getAsJsonObject("completionItem")["snippetSupport"].asBoolean)
    assertEquals(1, h.fake.platformSessions())
  }

  // The positive control for every "no platform notification" check below.
  @Test
  fun `a server showMessage of each type is captured`() = run {
    h.fake.start()
    open("q.partiql")
    connected()
    for (type in listOf(MessageType.Error, MessageType.Warning, MessageType.Info)) h.fake.notifyAll("window/showMessage", MessageParams(type, "m${type.value}"))
    waitFor("three notifications") { h.notifications.size == 3 }
    assertEquals(listOf(NotificationType.ERROR, NotificationType.WARNING, NotificationType.INFORMATION), h.notifications.map { it.type })
    assertEquals(listOf(true, true, true), h.notifications.mapIndexed { i, n -> "m${i + 1}" in n.content })
  }

  @Test
  fun `a refused token rereads the port file once, then shows only our sentence, also after the server drops the socket`() = run {
    h.fake.start()
    h.portFile.writeText("""{"port":${h.fake.port},"token":"wrong","pid":${ProcessHandle.current().pid()}}""")
    open("q.partiql")
    h.awaitStatus(Status.Disconnected(Reason.AUTH_FAILED))
    // The server drops each refused socket 250 ms later.
    delay(1000)
    assertEquals(2, h.fake.initializeParams.size)
    assertEquals(listOf(Reason.AUTH_FAILED.sentence), h.sentences())
    assertEquals(0, h.fake.platformSessions())
  }

  @Test
  fun `signed-out at initialize latches signed-out`() = run {
    h.fake.onInitialize = { throw h.fake.refusal("error-signed-out.json") }
    h.fake.start()
    open("q.partiql")
    h.awaitStatus(Status.Disconnected(Reason.SIGNED_OUT))
  }

  // A killed app leaves its port file behind.
  @Test
  fun `a port file whose port refuses the connection starts the app`() = run {
    h.fake.start()
    val app = h.installApp(h.serving())
    val closed = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
    h.portFile.writeText("""{"port":$closed,"token":"t","pid":${ProcessHandle.current().pid()}}""")
    open("q.partiql")
    connected()
    assertEquals(1, app.starts)
  }

  @Test
  fun `a server that closes the socket before it answers initialize latches connection-ended`() = run {
    h.fake.onInitialize = { h.fake.sessions.forEach { it.socket.close() } }
    h.fake.start()
    open("q.partiql")
    h.awaitStatus(Status.Disconnected(Reason.CONNECTION_ENDED))
    assertEquals(0, h.fake.platformSessions())
  }

  @Test
  fun `another initialize error latches start-failed`() = run {
    h.fake.onInitialize = { throw ResponseErrorException(ResponseError(-32603, "Internal error", null)) }
    h.fake.start()
    open("q.partiql")
    h.awaitStatus(Status.Disconnected(Reason.START_FAILED))
    assertEquals(0, h.fake.platformSessions())
  }

  // The app restarted with a new token: the reread finds it, and the refused socket's late close ends nothing.
  @Test
  fun `after a -32898 and a rotated port file, exactly one session lives`() = run {
    h.fake.start()
    val good = h.portFile.readText()
    h.portFile.writeText("""{"port":${h.fake.port},"token":"stale","pid":${ProcessHandle.current().pid()}}""")
    h.fake.onInitialize = {
      h.fake.onInitialize = null
      h.portFile.writeText(good)
    }
    open("q.partiql")
    connected()
    delay(1000)
    assertEquals(3, h.fake.initializeParams.size)
    assertEquals(1, liveSessions())
    assertTrue(h.core.status is Status.Connected)
  }

  @Test
  fun `a platform initialize refused after the preflight passed latches and leaves no client`() = run {
    h.fake.afterInitialize = {
      h.fake.afterInitialize = null
      h.fake.token = "rotated"
    }
    h.fake.start()
    open("q.partiql")
    h.awaitStatus(Status.Disconnected(Reason.START_FAILED))
    waitFor("no client") { h.clients().isEmpty() }
  }

  @Test
  fun `the connect-time listing - signed-out stays connected, profile-required is unbound, -32601 latches and ends the session`() = run {
    h.fake.handle("dynotable/listTables") { _, _ -> throw h.fake.refusal("error-signed-out.json") }
    h.fake.start()
    open("q.partiql")
    connected()
    waitFor("the connect-time listing") { h.fake.received.any { it.method == "dynotable/listTables" } }
    delay(500)
    assertTrue(h.core.status is Status.Connected)

    h.fake.handle("dynotable/listTables") { _, _ -> throw h.fake.refusal("error-profile-required.json") }
    h.core.perform(Action.RECONNECT)
    waitFor("unbound") { h.core.status is Status.Unbound }

    h.fake.handle("dynotable/listTables") { _, _ -> throw ResponseErrorException(ResponseError(-32601, "Unhandled method", null)) }
    h.core.perform(Action.RECONNECT)
    h.awaitStatus(Status.Disconnected(Reason.MISSING_REQUEST))
    waitFor("the session to end") { liveSessions() == 0 && h.clients().isEmpty() }
  }

  @Test
  fun `an app below the minimum version latches update-app and starts no platform session`() = run {
    h.fake.version = "1.0.0"
    h.fake.start()
    open("q.partiql")
    h.awaitStatus(Status.Disconnected(Reason.UPDATE_APP))
    open("after.partiql")
    h.core.machine.awaitIdle()
    assertEquals(1, h.fake.initializeParams.size)
    assertEquals(0, h.fake.platformSessions())
  }

  @Test
  fun `a platform Restart ends connected on the same descriptor, and a platform Stop waits for the next trigger`() = run {
    h.fake.start()
    open("q.partiql")
    val attempt = connected()
    h.platformRestart(h.clients().single())
    waitFor("a second platform session") { h.fake.platformSessions() == 2 }
    h.awaitStatus(Status.Connected(attempt))
    assertFalse(Reason.CONNECTION_ENDED.sentence in h.sentences())

    h.platformStop(h.clients().single())
    h.awaitStatus(Status.Disconnected(Reason.STOPPED))
    delay(1000)
    assertEquals(2, h.fake.platformSessions())
    open("next.partiql")
    assertTrue(connected() > attempt)
  }

  // An editor that the IDE restores at startup fires no file-open event; without the activity it stays unserved.
  @Test
  fun `the startup activity connects for a DynoTable file that is already open`() = run {
    h.fake.start()
    open("restored.partiql")
    connected()
    // After a platform Stop, no other trigger connects.
    h.platformStop(h.clients().single())
    h.awaitStatus(Status.Disconnected(Reason.STOPPED))
    RestoredEditors().execute(project.get())
    connected()
  }

  // An app the user quit must never come back invisibly, and no red "Terminated" client stays.
  @Test
  fun `the app quits - connection-ended, the next file open spawns nothing, and no client stays`() = run {
    val app = h.installApp()
    h.fake.start()
    open("q.partiql")
    connected()
    h.fake.close()
    h.awaitStatus(Status.Disconnected(Reason.CONNECTION_ENDED))
    open("next.partiql")
    h.core.machine.awaitIdle()
    assertEquals(0, app.starts)
    waitFor("no client") { h.clients().isEmpty() }
  }

  @Test
  fun `a new port file reconnects`() = run {
    h.fake.start()
    open("q.partiql")
    val first = connected()
    FakeServer(h.portFile).use { second ->
      second.start()
      waitFor("a session on the new server") { second.platformSessions() == 1 }
      assertTrue(connected() > first)
      waitFor("the old session to end") { liveSessions() == 0 }
    }
  }

  // A delete is no event: the app can be in the middle of a rewrite, and a spawn would start a second app.
  @Test
  fun `a port file deleted while the app serves spawns nothing and keeps the session`() = run {
    val app = h.installApp(FakeRun.Hang)
    h.fake.start()
    open("q.partiql")
    val attempt = connected()
    val initializes = h.fake.initializeParams.size
    Files.delete(h.portFile)
    // The watcher polls every 500 ms; a fixed wait is the only sign that it saw the delete.
    delay(1500)
    assertEquals(Status.Connected(attempt), h.core.status)
    assertEquals(initializes, h.fake.initializeParams.size)
    assertEquals(0, app.starts)
  }

  // Only Reconnect or a new port file clears a latch.
  @Test
  fun `a sqlFiles change while latched starts nothing`() = run {
    open("q.partiql")
    h.awaitStatus(Status.Disconnected(Reason.NOT_INSTALLED))
    val app = h.installApp(FakeRun.Hang)
    val settings = DynoTableProjectSettings.getInstance(project.get())
    try {
      withContext(Dispatchers.EDT) { settings.sqlFiles = listOf("**/*.a.sql") }
      // The change only schedules a restart, so a fixed wait.
      delay(1000)
      h.core.machine.awaitIdle()
      assertEquals(Status.Disconnected(Reason.NOT_INSTALLED), h.core.status)
      assertEquals(0, app.starts)
    } finally {
      withContext(Dispatchers.EDT) { settings.sqlFiles = emptyList() }
    }
  }

  // Every project has the widget; it must not start the port-file watcher of a project with no DynoTable file.
  @Test
  fun `the status widget of a project with no DynoTable file starts no connection`() {
    assertEquals("", StatusWidget(project.get()).getText())
    assertNull(project.get().getServiceIfCreated(ConnectCore::class.java))
  }

  // Repeated settings changes during a start would each replace the session the one before made.
  @Test
  fun `a restart storm - two sqlFiles changes during a slow start give two more platform sessions and one live session`() = run {
    h.fake.start()
    open("q.partiql")
    connected()
    val before = h.fake.platformSessions()
    val initializes = h.fake.initializeParams.size
    h.fake.initializeDelayMs = 1500
    h.core.perform(Action.RECONNECT)
    waitFor("a start in flight") { h.fake.initializeParams.size > initializes }
    val settings = DynoTableProjectSettings.getInstance(project.get())
    withContext(Dispatchers.EDT) {
      settings.sqlFiles = listOf("**/*.a.sql")
      settings.sqlFiles = listOf("**/*.b.sql")
    }
    waitFor("two more platform sessions", 30_000) { h.fake.platformSessions() == before + 2 }
    connected()
    delay(500)
    assertEquals(before + 2, h.fake.platformSessions())
    assertEquals(1, liveSessions())
    val client = h.clients().single()
    val tables = client.sendRequest { (it as DynoTableServer).listTables(JsonObject()) }
    assertEquals(h.fake.fixture("list-tables.json"), tables)
    withContext(Dispatchers.EDT) { settings.sqlFiles = emptyList() }
  }

  @Test
  fun `a closing project opens no preflight and starts no client`() = run {
    h.fake.start()
    h.core
    ConnectClosing().projectClosing(project.get())
    open("q.partiql")
    delay(1000)
    assertEquals(0, h.fake.initializeParams.size)
  }

  @Test
  fun `an untrusted project spawns nothing, cancelling Trust Project keeps it untrusted, and trusting it connects`() = run {
    val property = "idea.trust.headless.disabled"
    val old = System.setProperty(property, "false")
    val dialogs = mutableListOf<String>()
    var answer = Messages.NO
    val oldDialog = TestDialogManager.setTestDialog(TestDialog { message -> dialogs += message; answer })
    try {
      TrustedProjects.setProjectTrusted(project.get(), false)
      val app = h.installApp(h.serving())
      open("q.partiql")
      h.awaitStatus(Status.Disconnected(Reason.UNTRUSTED))
      delay(1000)
      assertEquals(0, app.starts)
      assertEquals(0, h.fake.initializeParams.size)

      h.core.perform(Action.TRUST_PROJECT)
      waitFor("the trust dialog") { dialogs.size == 1 }
      delay(500)
      assertFalse(TrustedProjects.isProjectTrusted(project.get()))
      assertEquals(Status.Disconnected(Reason.UNTRUSTED), h.core.status)

      h.fake.start()
      answer = Messages.YES
      h.core.perform(Action.TRUST_PROJECT)
      connected()
    } finally {
      TestDialogManager.setTestDialog(oldDialog)
      if (old == null) System.clearProperty(property) else System.setProperty(property, old)
    }
  }

  // The platform calls fileOpened under a read lock.
  @Test
  fun `opening a file returns at once while the server answers initialize slowly`() = run {
    h.fake.initializeDelayMs = 3000
    h.fake.start()
    open("q.partiql")
    assertFalse(h.core.status is Status.Connected)
    connected()
  }

  // Decision 6: a command sent before the server opened the file is refused.
  @Test
  fun `a file is open on the server when its diagnostics arrive, and a closed file is not`() = run {
    h.fake.start()
    val file = open("q.partiql")
    connected()
    // A wait to the 3 s deadline would mean the sign did not end it.
    assertTrue(measureTimeMillis { assertTrue(h.core.awaitServerOpened(file)) } < 3000)
    assertTrue(h.fake.opened(file.url))
    withContext(Dispatchers.EDT) { FileEditorManager.getInstance(project.get()).closeFile(file) }
    assertFalse(h.core.awaitServerOpened(file))
  }

  // No app is installed and no port file exists, so every attempt latches not-installed.
  @Test
  fun `a command shows the reason once, from idle and when already latched, and a file open shows nothing more`() = run {
    assertEquals(Status.Hidden, h.core.status)
    assertNull(h.core.client())
    assertEquals(listOf(Reason.NOT_INSTALLED.sentence), h.sentences())
    assertNull(h.core.client())
    open("q.partiql")
    delay(1000)
    assertEquals(listOf(Reason.NOT_INSTALLED.sentence, Reason.NOT_INSTALLED.sentence), h.sentences())
  }

  @Test
  fun `Download from the status widget and the Open Documentation action open their pages`() = run {
    val opened = CopyOnWriteArrayList<String>()
    h.core.openExternal = { opened += it }
    h.core.client()
    h.popupAnswer = Action.DOWNLOAD.label
    withContext(Dispatchers.EDT) { StatusWidget(project.get()).showActions() }
    assertEquals(Reason.NOT_INSTALLED.actions.map { it.label }, h.popups.single().map { it.text })
    withContext(Dispatchers.EDT) {
      val action = ActionManager.getInstance().getAction("DynoTable.OpenDocs")
      ActionUtil.invokeAction(action, SimpleDataContext.getProjectContext(project.get()), "test", null, null)
    }
    assertEquals(listOf(DOWNLOAD_URL, DOCS_URL), opened)
  }

  // The not-installed fix is Set App Path, then Reconnect.
  @Test
  fun `a changed appPath applies at Reconnect, and Open DynoTable from a reason starts the app without --headless`() = run {
    val app = FakeApp(h.appRoot.resolve("custom"), listOf(FakeRun.Exit(1), FakeRun.Hang))
    val settings = DynoTableAppSettings.getInstance()
    try {
      h.core.client()
      settings.appPath = app.binary.toString()
      open("q.partiql")
      delay(500)
      assertEquals(0, app.starts)
      h.core.perform(Action.RECONNECT)
      h.awaitStatus(Status.Disconnected(Reason.START_FAILED))
      assertEquals(listOf("--headless"), app.argv(1))
      // The status changes before its notification is posted.
      waitFor("the latch's notification") { h.notifications.any { it.content == Reason.START_FAILED.sentence } }
      h.click(h.notifications.last { it.content == Reason.START_FAILED.sentence }, Action.OPEN_APP.label)
      waitFor("the windowed start") { app.starts == 2 }
      assertEquals(emptyList<String>(), app.argv(2))
    } finally {
      settings.appPath = ""
      app.close()
    }
  }

  @Test
  fun `a changed userDataDir applies at once`() = run {
    val settings = DynoTableAppSettings.getInstance()
    h.core.client()
    try {
      settings.userDataDir = h.appRoot.resolveSibling("custom-user-data").toString()
      h.awaitStatus(Status.Disconnected(Reason.LAUNCHER_OFF))
      settings.userDataDir = ""
      h.awaitStatus(Status.Disconnected(Reason.NOT_INSTALLED))
    } finally {
      settings.userDataDir = ""
    }
  }

  @Test
  fun `a plain sql file and another file start nothing, and a sqlFiles change serves the open sql file`() = run {
    h.fake.start()
    val sql = open("plain.sql")
    open("notes.txt")
    delay(1000)
    assertEquals(0, h.fake.initializeParams.size)
    withContext(Dispatchers.EDT) { DynoTableProjectSettings.getInstance(project.get()).sqlFiles = listOf("plain.sql") }
    waitFor("didOpen of the sql file") { h.fake.language(sql.url) == "sql" }
  }

  @Test
  fun `a partiql file outside the project is served`() = run {
    h.fake.start()
    val path = h.appRoot.resolveSibling("outside.partiql").apply { writeText("SELECT 1") }
    val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
    withContext(Dispatchers.EDT) { fixture.openFileInEditor(file) }
    waitFor("didOpen of the outside file") { h.fake.opened(file.url) }
  }
}
