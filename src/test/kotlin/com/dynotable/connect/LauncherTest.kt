package com.dynotable.connect

import com.dynotable.fake.FakeApp
import com.dynotable.fake.FakeRun
import com.dynotable.protocol.PortFile
import com.dynotable.status.Reason
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** Runs a fake binary (a shell script under the test app root), never the real app. */
@DisabledOnOs(OS.WINDOWS)
class LauncherTest {
  @TempDir lateinit var tmp: Path
  private lateinit var apps: Path
  private lateinit var home: Path
  private lateinit var portFile: Path
  private val fakes = mutableListOf<FakeApp>()

  @BeforeEach
  fun setUp() {
    apps = tmp.resolve("apps")
    home = tmp.resolve("home").createDirectories()
    portFile = tmp.resolve("userData/lsp-port.json")
  }

  @AfterEach
  fun killFakes() = fakes.forEach { it.close() }

  private fun fake(vararg runs: FakeRun, dir: Path = apps, executable: Boolean = true) =
    FakeApp(dir, runs.toList(), executable).also { fakes += it }

  private fun launcher(
    app: AppLocation?,
    platform: Platform = Platform.current(),
    timeouts: LaunchTimeouts = LaunchTimeouts(coldStartMs = 10_000, nudgeWaitMs = 1_000, pollMs = 50),
    launcherOff: Boolean = false,
    perl: String = PERL,
    spawner: Spawner = Spawner.of(testMode = true, testAppRoot = apps.toString()),
  ) = Launcher(
    platform,
    home.toString(),
    mapOf("PATH" to "/usr/bin:/bin", "HOME" to home.toString(), "AWS_PROFILE" to "prod", "NODE_OPTIONS" to "--inspect"),
    MIN_APP_VERSION,
    { launcherOff },
    { app },
    { readPortFile(Fs.Local, portFile.toString()) },
    spawner,
    timeouts,
    perl,
  )

  private fun at(fake: FakeApp, version: String? = null) = AppLocation(fake.binary.toString(), version = version)

  /** Read after the launch: the pid is known only once the fake ran. */
  private fun ready(fake: FakeApp, start: Int, port: Int, token: String) =
    LaunchResult.Ready(PortFile(port, token, fake.pid(start)))

  @Test
  fun `a cold start returns the new port file of a detached app with --headless, the filtered env and cwd home`() {
    val app = fake(FakeRun.Serve(portFile, 41121, "new"))
    val result = runBlocking { launcher(at(app)).launch(stale = null) }
    assertEquals(ready(app, 1, 41121, "new"), result)
    assertEquals(listOf("--headless"), app.argv(1))
    // The shell adds its own PWD, SHLVL and `_`. Nothing of the IDE's own environment reaches the app.
    assertEquals(mapOf("PATH" to "/usr/bin:/bin", "HOME" to home.toString()), app.env(1) - setOf("PWD", "SHLVL", "_"))
    assertEquals(home.toRealPath().toString(), app.cwd(1))
    // A child in the IDE's process group dies on Ctrl+C in the terminal that started the IDE.
    assertEquals(app.pid(1), app.processGroup(1))
  }

  @Test
  fun `with no perl the app starts plainly`() {
    val app = fake(FakeRun.Serve(portFile, 41121, "new"))
    val result = runBlocking { launcher(at(app), perl = tmp.resolve("no-perl").toString()).launch(null) }
    assertEquals(ready(app, 1, 41121, "new"), result)
  }

  @ParameterizedTest
  @CsvSource("1, START_FAILED", "3, INTEGRATION_OFF", "7, CRASHED")
  fun `an exit code latches its reason`(code: Int, reason: Reason) {
    val app = fake(FakeRun.Exit(code))
    assertEquals(LaunchResult.Failed(reason), runBlocking { launcher(at(app)).launch(null) })
  }

  // After exit 0 the stale file is still there; its key would send the token to a dead or foreign port.
  @Test
  fun `after exit 0 the wait skips the stale key, and one more spawn serves`() {
    portFile.parent.createDirectories()
    portFile.writeText("""{"port":41120,"token":"old"}""")
    val app = fake(FakeRun.Exit(0), FakeRun.Serve(portFile, 41121, "new"))
    val result = runBlocking { launcher(at(app)).launch(stale = PortFile(41120, "old", null)) }
    assertEquals(ready(app, 2, 41121, "new"), result)
    assertEquals(2, app.starts)
  }

  @Test
  fun `exit 0 twice with no new port file is not-serving, with no third spawn`() {
    val app = fake(FakeRun.Exit(0), FakeRun.Exit(0), FakeRun.Exit(0))
    val timeouts = LaunchTimeouts(coldStartMs = 10_000, nudgeWaitMs = 300, pollMs = 50)
    assertEquals(LaunchResult.Failed(Reason.NOT_SERVING), runBlocking { launcher(at(app), timeouts = timeouts).launch(null) })
    assertEquals(2, app.starts)
  }

  @Test
  fun `a live child that writes no port file times out`() {
    val app = fake(FakeRun.Hang)
    val timeouts = LaunchTimeouts(coldStartMs = 500, nudgeWaitMs = 1_000, pollMs = 50)
    assertEquals(LaunchResult.Failed(Reason.TIMED_OUT), runBlocking { launcher(at(app), timeouts = timeouts).launch(null) })
  }

  // The detach wrapper runs the binary through `exec`; a failed `exec` must not look like a nudge.
  @Test
  fun `a binary that cannot run is spawn-failed`() {
    val app = fake(FakeRun.Exit(0), executable = false)
    assertEquals(LaunchResult.Failed(Reason.SPAWN_FAILED), runBlocking { launcher(at(app)).launch(null) })
  }

  // An old app reads --headless as a plain launch and opens its full window.
  @ParameterizedTest
  @CsvSource("launcher off, LAUNCHER_OFF", "no app, NOT_INSTALLED", "old app, UPDATE_APP")
  fun `a refused launch spawns nothing`(case: String, reason: Reason) {
    val app = fake(FakeRun.Serve(portFile, 41121, "new"))
    val launcher = when (case) {
      "launcher off" -> launcher(at(app), launcherOff = true)
      "no app" -> launcher(null)
      else -> launcher(at(app, version = "1.18.2"))
    }
    assertEquals(LaunchResult.Failed(reason), runBlocking { launcher.launch(null) })
    assertEquals(0, app.starts)
  }

  @Test
  fun `Open DynoTable on macOS opens the bundle, with no --headless`() {
    val started = mutableListOf<ProcessBuilder>()
    val app = AppLocation("/A/DynoTable.app/Contents/MacOS/DynoTable", "/A/DynoTable.app", "1.19.0")
    assertNull(launcher(app, Platform.MAC, spawner = { started += it; NoProcess }).openApp())
    val builder = started.single()
    assertEquals(listOf("/usr/bin/open", "-a", "/A/DynoTable.app"), builder.command())
    assertEquals(mapOf("PATH" to "/usr/bin:/bin", "HOME" to home.toString()), builder.environment())
    assertEquals(home.toFile(), builder.directory())
  }

  @Test
  fun `Open DynoTable elsewhere runs the binary with no --headless`() {
    val app = fake(FakeRun.Exit(0))
    assertNull(launcher(at(app), Platform.LINUX).openApp())
    waitFor { app.starts == 1 }
    assertEquals(emptyList<String>(), app.argv(1))
    assertEquals(mapOf("PATH" to "/usr/bin:/bin", "HOME" to home.toString()), app.env(1) - setOf("PWD", "SHLVL", "_"))
  }

  // With the userDataDir setting, a plain launch would start a second app on the default user data.
  @ParameterizedTest
  @CsvSource("launcher off, LAUNCHER_OFF", "no app, NOT_INSTALLED", "cannot run, SPAWN_FAILED")
  fun `Open DynoTable refuses`(case: String, reason: Reason) {
    val app = fake(FakeRun.Exit(0), executable = case != "cannot run")
    val launcher = when (case) {
      "launcher off" -> launcher(at(app), Platform.LINUX, launcherOff = true)
      "no app" -> launcher(null, Platform.LINUX)
      else -> launcher(at(app), Platform.LINUX)
    }
    assertEquals(reason, launcher.openApp())
    assertEquals(0, app.starts)
  }

  // Every form must be refused: the outside fake stands for the real app.
  @ParameterizedTest
  @CsvSource(
    "bundle as an argument", "detach wrapper", "no test app root", "dot-dot out of the root", "sibling root with the same prefix",
    "relative path", "link under the root", "perl with another script",
  )
  fun `in test mode no other binary starts`(form: String) {
    val outside = fake(FakeRun.Exit(0), dir = tmp.resolve("other"))
    val sibling = fake(FakeRun.Exit(0), dir = tmp.resolve("apps2"))
    val inside = fake(FakeRun.Exit(0))
    val guard = Spawner.of(testMode = true, testAppRoot = apps.toString())
    val (spawner, command) = when (form) {
      // As `open -a <bundle>` would.
      "bundle as an argument" -> guard to listOf(outside.binary.toString(), inside.binary.toString())
      "detach wrapper" -> guard to DETACH + outside.binary.toString()
      "no test app root" -> Spawner.of(testMode = true, testAppRoot = null) to listOf(inside.binary.toString())
      "dot-dot out of the root" -> guard to listOf("$apps/../other/dynotable")
      "sibling root with the same prefix" -> guard to listOf(sibling.binary.toString())
      "relative path" -> guard to listOf(tmp.relativize(inside.binary).toString())
      "link under the root" -> guard to listOf(Files.createSymbolicLink(apps.resolve("link"), outside.binary).toString())
      else -> guard to listOf(PERL, "-e", "exec \"${outside.binary}\"", "x", inside.binary.toString())
    }
    assertThrows<IllegalStateException> { spawner.start(ProcessBuilder(command)) }
    assertEquals(0, outside.starts + sibling.starts + inside.starts)
  }

  @Test
  fun `in test mode no page opens`() {
    assertThrows<IllegalStateException> { openExternal(testMode = true)("https://dynotable.com/download") }
  }

  private fun waitFor(condition: () -> Boolean) {
    val deadline = System.nanoTime() + 10_000_000_000
    while (!condition()) {
      check(System.nanoTime() < deadline) { "timed out" }
      Thread.sleep(20)
    }
  }

  private object NoProcess : Process() {
    override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

    override fun getInputStream(): InputStream = InputStream.nullInputStream()

    override fun getErrorStream(): InputStream = InputStream.nullInputStream()

    override fun waitFor() = 0

    override fun exitValue() = 0

    override fun destroy() {}
  }
}
