package com.dynotable.live

import com.dynotable.connect.Platform
import com.dynotable.connect.launchEnv
import com.dynotable.harness.waitFor
import com.dynotable.status.Action
import com.dynotable.status.Reason
import com.dynotable.status.Status
import com.google.gson.JsonParser
import com.intellij.openapi.application.EDT
import com.intellij.openapi.util.text.StringUtil
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.minutes

private fun escaped(reason: Reason) = StringUtil.escapeXmlEntities(reason.sentence)

/** Compared, never printed: it holds the token. */
private fun key() = LiveApp.port()?.let { it.port to it.token }

/**
 * The plugin starts, finds and loses the real app. A signal or a file change stands in for what a
 * person does in the app. Each test makes its own start state.
 */
@TestApplication
@Tag("live")
@EnabledOnOs(OS.MAC)
class LiveLauncherTest {
  private val tempDir = tempPathFixture()
  private val project = projectFixture(tempDir, openAfterCreation = true)
  private val module = project.moduleFixture(tempDir, addPathToSourceRoot = true)
  private val fixture by codeInsightFixture(project, tempDir)
  private val live by liveFixture(project)

  private val h get() = live.h
  private val dir get() = LiveApp.dir

  private fun run(test: suspend () -> Unit) = timeoutRunBlocking(4.minutes) { test() }

  private suspend fun open(name: String, text: String) = withContext(Dispatchers.EDT) { fixture.configureByText(name, text) }

  /** A serving app with a bound session on it: its pid. */
  private suspend fun connectedApp(): Long {
    live.session()
    if (h.core.status is Status.Unbound) live.bind()
    assertTrue(h.core.status is Status.Connected, "${h.core.status}")
    return LiveApp.servingPid()
  }

  private fun onlyHeadless(spawned: List<Spawned>) =
    assertEquals(listOf(listOf(LiveApp.wrapper, "--headless")), spawned.map { it.command.takeLast(2) })

  private suspend fun quit(pid: Long) {
    signalApp(pid, dir, Signal.TERM)
    // A clean quit removes the port file.
    waitFor("the port file to go", START_MS) { LiveApp.port() == null }
    waitFor("every app process to go", START_MS) { appProcesses(dir).isEmpty() }
  }

  private fun setEnabled(enabled: Boolean) {
    val config = Path.of(dir, "lsp-config.json")
    val json = JsonParser.parseString(config.readText()).asJsonObject
    json.addProperty("enabled", enabled)
    config.writeText(json.toString())
  }

  @Test
  fun `with no app, a file open starts the wrapper headless once, the app outlives the client, and the next connect starts nothing`() = run {
    stopApps(dir)
    val before = key()
    live.clearSpawns()
    open("session.partiql", "SELECT 1")
    waitFor("a session, now ${h.core.status}", START_MS) { h.core.status.let { it is Status.Connected || it is Status.Unbound } }
    onlyHeadless(live.spawned)
    assertTrue(key() != before, "the port file has no new (port, token)")
    val pid = LiveApp.servingPid()
    // The detach wrapper and the app wrapper exec, so the spawned pid serves.
    assertEquals(pid, live.spawned.single().process?.pid())
    live.bind()
    live.runOrders(fixture)

    h.stopClients()
    h.awaitStatus(Status.Disconnected(Reason.STOPPED))
    assertTrue(alive(pid), "the app quit with its client")
    assertEquals(pid, LiveApp.port()?.pid)
    h.core.perform(Action.RECONNECT)
    waitFor("connected, now ${h.core.status}", START_MS) { h.core.status is Status.Connected }
    live.runOrders(fixture)
    assertEquals(1, live.spawned.size)
    assertEquals(pid, LiveApp.servingPid())
  }

  // No plugin path launches while an app serves, so the test starts the second instance.
  @Test
  fun `a second headless launch exits 0 and the first app keeps serving`() = run {
    val pid = connectedApp()
    val client: LspClient? = h.core.serving()
    val before = key()
    val builder = ProcessBuilder(LiveApp.wrapper, "--headless")
      .directory(File(System.getProperty("user.home")))
      .redirectOutput(ProcessBuilder.Redirect.DISCARD)
      .redirectError(ProcessBuilder.Redirect.DISCARD)
    builder.environment().clear()
    builder.environment().putAll(launchEnv(Platform.current(), System.getenv()))
    val second = guardedSpawner(LiveApp.wrapper).start(builder)
    try {
      assertTrue(withContext(Dispatchers.IO) { second.waitFor(30, TimeUnit.SECONDS) }, "the second launch did not exit in 30 s")
    } finally {
      if (second.isAlive) second.destroy()
    }
    assertEquals(0, second.exitValue())
    // A nudge to a serving app changes nothing.
    delay(2_000)
    assertTrue(key() == before, "the port file changed after the nudge")
    assertEquals(pid, LiveApp.servingPid())
    assertSame(client, h.core.serving())
    live.runOrders(fixture)
  }

  @Test
  fun `with the integration off, the launched app exits 3 and the integration-off sentence shows once`() = run {
    quit(connectedApp())
    h.awaitStatus(Status.Disconnected(Reason.CONNECTION_ENDED))
    // Change the config only while no app runs on the directory.
    assertEquals(emptyList<AppProcess>(), appProcesses(dir))
    setEnabled(false)
    try {
      h.notifications.clear()
      live.clearSpawns()
      h.core.perform(Action.RECONNECT)
      h.awaitStatus(Status.Disconnected(Reason.INTEGRATION_OFF), START_MS)
      onlyHeadless(live.spawned)
      val exited = live.spawned.single().process!!
      assertTrue(withContext(Dispatchers.IO) { exited.waitFor(5, TimeUnit.SECONDS) })
      assertEquals(3, exited.exitValue())
      // A second notification would come now.
      delay(1_000)
      assertEquals(listOf(escaped(Reason.INTEGRATION_OFF)), h.sentences())
      assertEquals(emptyList<AppProcess>(), appProcesses(dir))
    } finally {
      setEnabled(true)
    }
  }

  @Test
  fun `after a SIGKILL, Reconnect starts a new app and a run works`() = run {
    val killed = connectedApp()
    h.notifications.clear()
    signalApp(killed, dir, Signal.KILL)
    h.awaitStatus(Status.Disconnected(Reason.CONNECTION_ENDED))
    assertEquals(listOf(escaped(Reason.CONNECTION_ENDED)), h.sentences())
    waitFor("the killed app to go") { !alive(killed) }
    assertEquals(killed, LiveApp.port()?.pid, "the stale port file is gone or changed")
    live.clearSpawns()
    h.core.perform(Action.RECONNECT)
    waitFor("connected, now ${h.core.status}", START_MS) { h.core.status is Status.Connected }
    onlyHeadless(live.spawned)
    val pid = LiveApp.servingPid()
    assertNotEquals(killed, pid)
    assertEquals(pid, live.spawned.single().process?.pid())
    live.runOrders(fixture)
  }

  @Test
  fun `after the app quits and starts again, the plugin connects again by itself`() = run {
    connectedApp()
    val attempt = h.core.session()!!
    live.clearSpawns()
    quit(LiveApp.servingPid())
    LiveApp.start()
    waitFor("a new session, now ${h.core.status}", START_MS) { h.core.session()?.let { it > attempt } == true }
    assertEquals(emptyList<Spawned>(), live.spawned)
  }

  @Test
  fun `an app quit ends the connection once, and nothing starts it`() = run {
    val pid = connectedApp()
    h.notifications.clear()
    live.clearSpawns()
    quit(pid)
    h.awaitStatus(Status.Disconnected(Reason.CONNECTION_ENDED))
    // A restart would come now.
    delay(5_000)
    assertEquals(Status.Disconnected(Reason.CONNECTION_ENDED), h.core.status)
    assertEquals(listOf(escaped(Reason.CONNECTION_ENDED)), h.sentences())
    assertEquals(emptyList<Spawned>(), live.spawned)
  }
}

/** The plugin's minimum app version is above the live app's. */
@TestApplication
@Tag("live")
@EnabledOnOs(OS.MAC)
class LiveVersionTest {
  private val tempDir = tempPathFixture()
  private val project = projectFixture(tempDir, openAfterCreation = true)
  private val module = project.moduleFixture(tempDir, addPathToSourceRoot = true)
  private val fixture by codeInsightFixture(project, tempDir)
  private val live by liveFixture(project, minAppVersion = "99.0.0")

  @Test
  fun `an app older than the minimum shows Update DynoTable once, and nothing starts`() = timeoutRunBlocking(4.minutes) {
    val h = live.h
    live.clearSpawns()
    withContext(Dispatchers.EDT) { fixture.configureByText("old.partiql", "SELECT 1") }
    h.awaitStatus(Status.Disconnected(Reason.UPDATE_APP), START_MS)
    // A reconnect or a start would come now.
    delay(1_000)
    assertEquals(listOf(escaped(Reason.UPDATE_APP)), h.sentences())
    assertEquals(emptyList<Spawned>(), live.spawned)
    assertEquals(emptyList<LspClient>(), h.clients())
  }
}
