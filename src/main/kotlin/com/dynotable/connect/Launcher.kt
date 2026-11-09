package com.dynotable.connect

import com.dynotable.protocol.PortFile
import com.dynotable.status.Reason
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

const val PERL = "/usr/bin/perl"

/**
 * A child in the IDE's session and process group dies on Ctrl+C or a terminal close. `exec` keeps
 * the pid, so the exit code passes through; `exit 127` reports an `exec` that failed.
 */
internal val DETACH = listOf(PERL, "-MPOSIX", "-e", "POSIX::setsid(); exec @ARGV; exit 127")
private const val EXEC_FAILED = 127
private const val OPEN = "/usr/bin/open"

sealed interface LaunchResult {
  data class Ready(val file: PortFile) : LaunchResult

  data class Failed(val reason: Reason) : LaunchResult
}

data class LaunchTimeouts(
  /** How long a cold `--headless` start may run with no new port file. */
  val coldStartMs: Long = 30_000,
  /** How long to wait for the port file after a running app was nudged (exit 0). */
  val nudgeWaitMs: Long = 20_000,
  val pollMs: Long = 250,
)

fun interface Spawner {
  /** Throws [IOException] when the OS cannot start the command. */
  fun start(builder: ProcessBuilder): Process

  companion object {
    /**
     * In test mode only a binary under [testAppRoot] may run, directly or through the exact detach
     * wrapper, so no test can start the real app or `open` anything. Links count where they point.
     */
    fun of(testMode: Boolean, testAppRoot: String?): Spawner = if (!testMode) {
      Spawner { it.start() }
    } else {
      Spawner { builder ->
        val command = builder.command()
        val program = if (command.first() == PERL) command.getOrNull(DETACH.size)?.takeIf { command.take(DETACH.size) == DETACH } else command.first()
        check(testAppRoot != null && program != null && isUnder(program, testAppRoot)) {
          "In tests only a fake binary under $TEST_APP_ROOT_PROPERTY may start, not $program"
        }
        builder.start()
      }
    }

    private fun isUnder(program: String, root: String): Boolean = try {
      Path.of(program).isAbsolute && Path.of(program).toRealPath().startsWith(Path.of(root).toRealPath())
    } catch (_: IOException) {
      false
    } catch (_: InvalidPathException) {
      false
    }
  }
}

/** The browser. In test mode it throws, so no test opens a page. */
fun openExternal(testMode: Boolean): (String) -> Unit =
  if (testMode) { url -> error("Opening $url is disabled in tests") } else BrowserUtil::browse

/** What a `--headless` child's exit code means. Any other code, or a signal, is a crash. */
private fun exitReason(code: Int, wrapped: Boolean): Reason? = when {
  code == 0 -> null
  code == 1 -> Reason.START_FAILED
  code == 3 -> Reason.INTEGRATION_OFF
  wrapped && code == EXEC_FAILED -> Reason.SPAWN_FAILED
  else -> Reason.CRASHED
}

/**
 * Starts the app. [env] is the IDE's environment; the app gets only [launchEnv] of it. [locate]
 * and [launcherOff] are read at each launch, so a changed setting applies.
 */
class Launcher(
  private val platform: Platform,
  private val home: String,
  private val env: Map<String, String>,
  private val minAppVersion: String,
  private val launcherOff: () -> Boolean,
  private val locate: () -> AppLocation?,
  private val readPortFile: () -> PortFile?,
  private val spawner: Spawner,
  private val timeouts: LaunchTimeouts = LaunchTimeouts(),
  private val perl: String = PERL,
) {
  /**
   * Starts the app with `--headless` and waits for a port file whose `(port, token)` differs from
   * [stale]. A serving app never exits; a second app hands over to the running one and exits `0`.
   */
  suspend fun launch(stale: PortFile?): LaunchResult {
    if (launcherOff()) return LaunchResult.Failed(Reason.LAUNCHER_OFF)
    val app = locate() ?: return LaunchResult.Failed(Reason.NOT_INSTALLED)
    // An old app reads --headless as a plain launch and opens its full window.
    if (app.version != null && !meetsMinimum(app.version, minAppVersion)) return LaunchResult.Failed(Reason.UPDATE_APP)
    // After a nudge with no new port file, the running app may have been quitting, so one more
    // spawn can become the serving one.
    repeat(2) {
      spawnHeadless(app.binary, stale)?.let { return it }
      waitForFile(stale, timeouts.nudgeWaitMs)?.let { return LaunchResult.Ready(it) }
    }
    return LaunchResult.Failed(Reason.NOT_SERVING)
  }

  /** Starts the app with its window, the route back to the app's own UI. `null` when it started. */
  fun openApp(): Reason? {
    // A plain launch uses the default user data, not the directory the user set.
    if (launcherOff()) return Reason.LAUNCHER_OFF
    val app = locate() ?: return Reason.NOT_INSTALLED
    val command = if (platform == Platform.MAC && app.bundle != null) listOf(OPEN, "-a", app.bundle) else listOf(app.binary)
    return if (start(command) == null) Reason.SPAWN_FAILED else null
  }

  /** `null` when the child exited `0`. */
  private suspend fun spawnHeadless(binary: String, stale: PortFile?): LaunchResult? {
    val wrapped = platform != Platform.WINDOWS && Files.isExecutable(Path.of(perl))
    if (platform != Platform.WINDOWS && !wrapped) {
      logger<Launcher>().warn("$perl is missing, so DynoTable starts in the IDE's session and stops with it")
    }
    val command = if (wrapped) listOf(perl) + DETACH.drop(1) + listOf(binary, "--headless") else listOf(binary, "--headless")
    val process = start(command) ?: return LaunchResult.Failed(Reason.SPAWN_FAILED)
    return try {
      withTimeout(timeouts.coldStartMs) { awaitStart(process, stale, wrapped) }
    } catch (_: TimeoutCancellationException) {
      LaunchResult.Failed(Reason.TIMED_OUT)
    }
  }

  /** `null` when the child exited `0`. */
  private suspend fun awaitStart(process: Process, stale: PortFile?, wrapped: Boolean): LaunchResult? {
    while (true) {
      fresh(stale)?.let { return LaunchResult.Ready(it) }
      if (!process.isAlive) return exitReason(process.exitValue(), wrapped)?.let { LaunchResult.Failed(it) }
      delay(timeouts.pollMs)
    }
  }

  private suspend fun waitForFile(stale: PortFile?, ms: Long): PortFile? = withTimeoutOrNull(ms) {
    var file = fresh(stale)
    while (file == null) {
      delay(timeouts.pollMs)
      file = fresh(stale)
    }
    file
  }

  private fun fresh(stale: PortFile?): PortFile? =
    readPortFile()?.takeIf { it.key != stale?.key }

  /** `null` when the OS cannot start it. */
  private fun start(command: List<String>): Process? {
    val builder = ProcessBuilder(command)
      // An inherited cwd is the IDE's folder, which Windows then locks against update or delete.
      .directory(File(home))
      .redirectInput(ProcessBuilder.Redirect.from(File(if (platform == Platform.WINDOWS) "NUL" else "/dev/null")))
      // Piped output fails with EPIPE in the app once the IDE exits.
      .redirectOutput(ProcessBuilder.Redirect.DISCARD)
      .redirectError(ProcessBuilder.Redirect.DISCARD)
    builder.environment().clear()
    builder.environment().putAll(launchEnv(platform, env))
    return try {
      spawner.start(builder)
    } catch (_: IOException) {
      null
    }
  }
}
