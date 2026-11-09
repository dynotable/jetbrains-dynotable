package com.dynotable.connect

import com.dynotable.harness.harnessFixture
import com.dynotable.harness.waitFor
import com.dynotable.settings.DynoTableWorkspaceSettings
import com.dynotable.settings.RequestedProfile
import com.dynotable.status.Action
import com.dynotable.status.Reason
import com.dynotable.status.Status
import com.intellij.configurationStore.saveSettings
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.logger
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.project.stateStore
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import java.util.logging.SimpleFormatter
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.minutes

/**
 * The token lets any local process act as the user in DynoTable. The plugin's own logs and the
 * saved `workspace.xml` must never hold it, also on the error paths.
 */
@TestApplication
@DisabledOnOs(OS.WINDOWS)
class TokenTest {
  private val tempDir = tempPathFixture()
  private val project = projectFixture(tempDir, openAfterCreation = true)
  private val module = project.moduleFixture(tempDir, addPathToSourceRoot = true)
  private val fixture by codeInsightFixture(project, tempDir)
  private val h by harnessFixture(project)

  /** Every log record of every category, as the log file shows it: message and stack trace. */
  private class Capture : Handler() {
    val lines = CopyOnWriteArrayList<String>()
    private val formatter = SimpleFormatter()

    override fun publish(record: LogRecord) {
      lines += formatter.format(record)
    }

    override fun flush() {}

    override fun close() {}
  }

  private suspend fun reconnect(expected: (Status) -> Boolean) {
    h.core.perform(Action.RECONNECT)
    waitFor("the status after Reconnect, now ${h.core.status}") { expected(h.core.status) }
  }

  @Test
  fun `no log line and no saved workspace file holds a token after a bad token, a rotated token, a malformed port file and a failed preflight`() =
    timeoutRunBlocking(2.minutes) {
      val capture = Capture().apply { level = Level.ALL }
      val root = Logger.getLogger("")
      // Held here: JUL keeps a logger only while something refers to it.
      val plugin = Logger.getLogger("#com.dynotable")
      val level = plugin.level
      root.addHandler(capture)
      plugin.level = Level.FINE
      try {
        val tokens = mutableSetOf<String>()
        fun write(token: String, complete: Boolean = true) {
          tokens += token
          val json = """{"port":${h.fake.port},"token":"$token","pid":${ProcessHandle.current().pid()}}"""
          h.portFile.writeText(if (complete) json else json.dropLast(10))
        }

        h.fake.start()
        val good = h.fake.token
        write("bad-${UUID.randomUUID()}")
        withContext(Dispatchers.EDT) { fixture.configureByText("q.partiql", "SELECT 1") }
        h.awaitStatus(Status.Disconnected(Reason.AUTH_FAILED))

        write(good)
        reconnect { it is Status.Connected }
        val first = (h.core.status as Status.Connected).attempt
        // The app restarted with a new token.
        h.fake.token = "rotated-${UUID.randomUUID()}"
        write(h.fake.token)
        reconnect { it is Status.Connected && it.attempt > first }

        write("malformed-${UUID.randomUUID()}", complete = false)
        reconnect { it == Status.Disconnected(Reason.NOT_INSTALLED) }

        h.fake.onInitialize = { throw h.fake.refusal("error-signed-out.json") }
        write(h.fake.token)
        reconnect { it == Status.Disconnected(Reason.SIGNED_OUT) }

        DynoTableWorkspaceSettings.getInstance(project.get()).profile = RequestedProfile("profile-id-1", "Production")
        saveSettings(project.get(), forceSavingAllSettings = true)
        val workspace = project.get().stateStore.workspacePath.readText()
        assertTrue("profile-id-1" in workspace, "the check reads a file the plugin did not save")
        assertEquals(emptyList<String>(), tokens.filter { it in workspace })

        // The control: a plugin debug line that holds the token is found. The log writes on its own
        // thread, so once the control shows, the lines before it show too.
        val control = "control ${h.fake.token}"
        logger<ConnectCore>().debug(control)
        waitFor("the control line in the capture") { capture.lines.any { control in it } }
        assertEquals(emptyList<String>(), capture.lines.filter { line -> control !in line && tokens.any { it in line } })
      } finally {
        plugin.level = level
        root.removeHandler(capture)
      }
    }
}
