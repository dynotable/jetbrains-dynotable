package com.dynotable.live

import com.dynotable.connect.AppLocation
import com.dynotable.connect.DETACH
import com.dynotable.connect.Fs
import com.dynotable.connect.LaunchResult
import com.dynotable.connect.Launcher
import com.dynotable.connect.MIN_APP_VERSION
import com.dynotable.connect.Overrides
import com.dynotable.connect.Platform
import com.dynotable.connect.Spawner
import com.dynotable.connect.TEST_MIN_APP_VERSION_PROPERTY
import com.dynotable.connect.TEST_USER_DATA_DIR_PROPERTY
import com.dynotable.connect.defaultUserDataDir
import com.dynotable.connect.portFilePath
import com.dynotable.connect.readPortFile
import com.dynotable.harness.Harness
import com.dynotable.harness.waitFor
import com.dynotable.profiles.ProfilePicker
import com.dynotable.protocol.PortFile
import com.dynotable.protocol.ProfileRow
import com.dynotable.editor.ServerCommands
import com.dynotable.results.Results
import com.dynotable.results.View
import com.dynotable.settings.DynoTableAppSettings
import com.dynotable.status.Status
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import com.intellij.testFramework.junit5.fixture.testFixture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Longer than the plugin's own cold-start wait. */
const val START_MS = 45_000L

/**
 * The executable that a live run may start: a wrapper that pins the app's user data directory and
 * runs a build that cannot update itself. Never an app bundle, a binary inside one, or anything
 * under /Applications.
 */
fun liveAppPath(env: Map<String, String>): String {
  val path = env["DYNOTABLE_LIVE_APP_PATH"]?.ifEmpty { null } ?: error("testLive needs DYNOTABLE_LIVE_APP_PATH.")
  val given = Path.of(path)
  require(given.isAbsolute) { "DYNOTABLE_LIVE_APP_PATH must be absolute." }
  requireOutsideBundles(given.normalize())
  require(Files.isRegularFile(given)) { "DYNOTABLE_LIVE_APP_PATH is not a file." }
  requireOutsideBundles(given.toRealPath())
  return path
}

private val BUNDLE = Regex("""\.app(/|$)""")

private fun requireOutsideBundles(path: Path) {
  val lower = path.toString().lowercase()
  require(!BUNDLE.containsMatchIn(lower) && !lower.startsWith("/applications/")) {
    "DYNOTABLE_LIVE_APP_PATH must be a wrapper outside app bundles and /Applications."
  }
}

/**
 * The app's user data directory for the live run. Never the real one, nor
 * DYNOTABLE_LIVE_DENY_USER_DATA_DIR, nor a directory inside or around either of them.
 */
fun liveUserDataDir(env: Map<String, String>, home: String, platform: Platform = Platform.current()): String {
  val dir = env["DYNOTABLE_LIVE_USER_DATA_DIR"]?.ifEmpty { null } ?: error("testLive needs DYNOTABLE_LIVE_USER_DATA_DIR.")
  require(Path.of(dir).isAbsolute) { "DYNOTABLE_LIVE_USER_DATA_DIR must be absolute." }
  val live = canonical(dir, platform)
  val denied = listOfNotNull(defaultUserDataDir(platform, env, home), env["DYNOTABLE_LIVE_DENY_USER_DATA_DIR"]?.ifEmpty { null })
  for (deny in denied.map { canonical(it, platform) }) {
    require(!live.startsWith(deny) && !deny.startsWith(live)) { "DYNOTABLE_LIVE_USER_DATA_DIR overlaps a user data directory that live tests must not touch." }
  }
  return Path.of(dir).normalize().toString()
}

/** The real path, also of a directory that does not exist yet. macOS and Windows ignore case. */
private fun canonical(path: String, platform: Platform): Path {
  val absolute = Path.of(path).toAbsolutePath().normalize()
  var existing = absolute
  while (!Files.exists(existing)) existing = existing.parent ?: break
  val real = if (Files.exists(existing)) existing.toRealPath().resolve(existing.relativize(absolute)) else absolute
  return if (platform == Platform.LINUX) real else Path.of(real.toString().lowercase())
}

/** One start of the app, as the spawner saw it. [process] stays `null` when the start was refused. */
class Spawned(val command: List<String>) {
  @Volatile
  var process: Process? = null
}

/** Records every start, the refused ones too, so a test sees them. */
class SpawnRecorder(private val inner: Spawner) : Spawner {
  val spawned = CopyOnWriteArrayList<Spawned>()

  override fun start(builder: ProcessBuilder): Process {
    val record = Spawned(builder.command().toList()).also { spawned += it }
    return inner.start(builder).also { record.process = it }
  }
}

/** A real start of `[app] --headless` only, directly or through the detach wrapper. Anything else, an Open DynoTable included, throws. */
fun guardedSpawner(app: String) = Spawner { builder ->
  val command = builder.command()
  val headless = listOf(app, "--headless")
  check(command == headless || command == DETACH + headless) { "Starting ${command.joinToString(" ")} is disabled in live tests" }
  builder.start()
}

data class AppProcess(val pid: Long, val main: Boolean)

/** The whole argument, so a directory that is a prefix of another one does not match it. */
private fun owns(command: String, dir: String) = " $command ".contains(" --user-data-dir=$dir ")

private val PS_LINE = Regex("""^\s*(\d+)\s+(.*)$""")

/** Every process of an app on [dir], helpers included. */
fun appProcesses(dir: String): List<AppProcess> = output("/bin/ps", "-axo", "pid=,command=").lines().mapNotNull { line ->
  val (pid, command) = PS_LINE.matchEntire(line)?.destructured ?: return@mapNotNull null
  if (owns(command, dir)) AppProcess(pid.toLong(), " --type=" !in command) else null
}

fun ownedBy(pid: Long, dir: String): Boolean = owns(output("/bin/ps", "-p", "$pid", "-o", "command=").trim(), dir)

fun alive(pid: Long): Boolean = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

enum class Signal { TERM, KILL }

/** Signals [pid] only when its command line names [dir]. */
fun signalApp(pid: Long, dir: String, signal: Signal) {
  check(ownedBy(pid, dir)) { "pid $pid is not an app on $dir; not signalled" }
  check(ProcessBuilder("/bin/kill", "-s", signal.name, "$pid").start().waitFor() == 0) { "kill -s ${signal.name} $pid failed" }
}

/** Quits every app on [dir] (SIGTERM), then kills what is left. Throws when a process stays. */
suspend fun stopApps(dir: String) {
  // A process can end between the listing and its signal; the wait below tells.
  appProcesses(dir).filter { it.main }.forEach { runCatching { signalApp(it.pid, dir, Signal.TERM) } }
  if (gone(dir, 15_000)) return
  appProcesses(dir).forEach { runCatching { signalApp(it.pid, dir, Signal.KILL) } }
  check(gone(dir, 5_000)) { "processes on $dir stay after SIGKILL: ${appProcesses(dir)}" }
}

private suspend fun gone(dir: String, ms: Long): Boolean = try {
  waitFor("the apps on $dir to quit", ms) { appProcesses(dir).isEmpty() }
  true
} catch (_: AssertionError) {
  false
}

private fun output(vararg command: String): String {
  val process = ProcessBuilder(*command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
  val text = process.inputStream.bufferedReader().readText()
  process.waitFor(10, TimeUnit.SECONDS)
  return text
}

/** The live run's settings, read from the environment once. */
object LiveApp {
  private val env: Map<String, String> = System.getenv()
  private val home: String = System.getProperty("user.home")

  val dir: String by lazy { liveUserDataDir(env, home) }
  val wrapper: String by lazy { liveAppPath(env) }

  /** The name or slug of a roster row with no app window open on it. */
  val profile: String get() = env["DYNOTABLE_LIVE_PROFILE"]?.ifEmpty { null } ?: error("testLive needs DYNOTABLE_LIVE_PROFILE.")

  /** Large enough that a run is still in flight when its cancel arrives. */
  val largeTable: String get() = env["DYNOTABLE_LIVE_LARGE_TABLE"]?.ifEmpty { null } ?: error("testLive needs DYNOTABLE_LIVE_LARGE_TABLE.")

  val portFile: Path get() = Path.of(dir, "lsp-port.json")

  @Volatile
  private var isolated = false

  /** The live port file. Never print its token. */
  fun port(): PortFile? = readPortFile(Fs.Local, portFile.toString())

  /** The one app on [dir], named by the port file. */
  fun servingPid(): Long {
    val pid = port()?.pid ?: error("no port file, or one with no pid")
    check(ownedBy(pid, dir)) { "the port file's pid $pid is not an app on $dir" }
    val mains = appProcesses(dir).filter { it.main }.map { it.pid }
    check(mains == listOf(pid)) { "the apps on $dir are $mains, the port file names $pid" }
    return pid
  }

  /** Starts the wrapper as the plugin does, when no app runs on [dir]; waits until one serves. */
  suspend fun ensureServing() {
    if (appProcesses(dir).none { it.main }) start()
    waitFor("an app that serves on $dir", START_MS) { port()?.pid?.let { ownedBy(it, dir) } == true }
  }

  /** Starts the wrapper as a person or another IDE would: the plugin has no part in it. Only when no app runs on [dir]. */
  suspend fun start(): PortFile {
    check(appProcesses(dir).none { it.main }) { "an app runs on $dir: a second start would only nudge it" }
    val launcher = Launcher(
      Platform.current(),
      home,
      env,
      MIN_APP_VERSION,
      launcherOff = { false },
      locate = { AppLocation(wrapper) },
      readPortFile = ::port,
      spawner = guardedSpawner(wrapper),
    )
    return when (val result = withContext(Dispatchers.IO) { launcher.launch(port()) }) {
      is LaunchResult.Ready -> result.file
      is LaunchResult.Failed -> error("the wrapper did not start an app that serves: ${result.reason}")
    }
  }

  /**
   * Before the first live test: the app that serves on [dir] opens no file under the real user
   * data directory, and writes no port file there. A release build can relaunch itself through its
   * updater on the real directory.
   */
  suspend fun ensureIsolated() {
    if (isolated) return
    val pids = appProcesses(dir).map { it.pid }
    val names = output("/usr/sbin/lsof", "-nP", "-Fn", "-p", pids.joinToString(",")).lines()
      .filter { it.startsWith("n") }.map { it.drop(1) }
    // A failed lsof lists nothing; the app always has files open in its own directory.
    check(names.any { it.startsWith("$dir/") }) { "lsof shows no open file of the apps on $dir" }
    val real = defaultUserDataDir(Platform.current(), env, home).lowercase()
    val leaks = names.filter { it.lowercase() == real || it.lowercase().startsWith("$real/") }
    check(leaks.isEmpty()) { "an app on $dir has files open in the real user data directory: $leaks" }
    // Only the time stamp: a test never reads the real port file.
    val realPortFile = Path.of(defaultUserDataDir(Platform.current(), env, home), "lsp-port.json")
    val started = pids.mapNotNull { ProcessHandle.of(it).flatMap { p -> p.info().startInstant() }.orElse(null) }.minOrNull()
      ?: error("the start time of the apps on $dir is unknown")
    check(!Files.exists(realPortFile) || Files.getLastModifiedTime(realPortFile).toInstant() < started) {
      "the real port file changed after an app on $dir started"
    }
    isolated = true
  }
}

/** The world of one live test: a [Harness] whose plugin reads the live port file and may start only the wrapper. */
class Live(val h: Harness, val spawns: SpawnRecorder) {
  val project: Project get() = h.project

  /** The starts of the app since the last [clearSpawns]. */
  val spawned: List<Spawned> get() = spawns.spawned.toList()

  fun clearSpawns() = spawns.spawned.clear()

  /** Connects, and waits for a session, bound or not. */
  suspend fun session(ms: Long = START_MS) {
    h.core.trigger()
    waitFor("a session, now ${h.core.status}", ms) { h.core.status.let { it is Status.Connected || it is Status.Unbound } }
  }

  /** Chooses the DYNOTABLE_LIVE_PROFILE row in the profile picker, and waits for the bound session. */
  suspend fun bind(): ProfileRow {
    session()
    val before = h.core.session() ?: error("no session")
    val client = h.core.serving() ?: error("no session")
    val roster = ServerCommands.getInstance(project).custom(client, "dynotable/listProfiles") { it.listProfiles() } ?: error("no roster: ${h.sentences()}")
    val row = ProfileRow.listFrom(roster.value).singleOrNull { it.name == LiveApp.profile || it.slug == LiveApp.profile }
      ?: error("DYNOTABLE_LIVE_PROFILE names no roster row")
    h.popupAnswer = row.name
    ProfilePicker.getInstance(project).select()
    waitFor("a new bound session, now ${h.core.status}", START_MS) { (h.core.status as? Status.Connected)?.let { it.attempt > before } == true }
    return row
  }

  suspend fun run(editor: Editor) = ServerCommands.getInstance(project).run(editor)

  /** The result the results tool window shows. */
  fun result(): View.Result = Results.getInstance(project).view.let { it as? View.Result ?: error("the results show $it, notifications ${h.sentences()}") }

  /** Runs a PartiQL SELECT on the seeded `orders` table; it must show rows and no notification. */
  suspend fun runOrders(fixture: CodeInsightTestFixture) {
    h.notifications.clear()
    run(withContext(Dispatchers.EDT) { fixture.configureByText("orders.partiql", "SELECT * FROM \"orders\"").let { fixture.editor } })
    check(h.sentences().isEmpty()) { "notifications: ${h.sentences()}" }
    check(result().shown.cells.isNotEmpty()) { "the run returned no rows" }
  }

  /** The sentence of a contract fixture, as a notification shows it. */
  fun sentence(file: String): String = StringUtil.escapeXmlEntities(h.fake.fixture(file).asJsonObject["message"].asString)
}

/**
 * The [Live] world for one test. The plugin reads the port file of the live user data directory,
 * starts only the wrapper, and checks the app version against [minAppVersion]. After the test, an
 * app serves on that directory again.
 */
fun liveFixture(project: TestFixture<Project>, minAppVersion: String? = null): TestFixture<Live> = testFixture("live") {
  LiveApp.ensureServing()
  LiveApp.ensureIsolated()
  val disposable = Disposer.newDisposable("live")
  val h = Harness(project.init(), tempPathFixture().init(), disposable)
  val seams = mapOf(TEST_USER_DATA_DIR_PROPERTY to LiveApp.dir) + listOfNotNull(minAppVersion?.let { TEST_MIN_APP_VERSION_PROPERTY to it })
  seams.forEach { (key, value) -> System.setProperty(key, value) }
  val settings = DynoTableAppSettings.getInstance()
  val appPath = settings.appPath
  settings.appPath = LiveApp.wrapper
  val home = System.getProperty("user.home")
  check(portFilePath(Platform.current(), System.getenv(), home, settings.userDataDir, Overrides.of(true, System::getProperty)) == LiveApp.portFile.toString()) {
    "the plugin does not read the live port file"
  }
  val spawns = SpawnRecorder(guardedSpawner(LiveApp.wrapper))
  h.core.spawner = spawns
  initialized(Live(h, spawns)) {
    try {
      h.close()
      LiveApp.ensureServing()
    } finally {
      settings.appPath = appPath
      seams.keys.forEach(System::clearProperty)
      Disposer.dispose(disposable)
    }
  }
}
