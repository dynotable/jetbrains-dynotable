package com.dynotable.connect

import com.dynotable.protocol.PortFile
import com.dynotable.protocol.ProtocolException
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.text.SemVer
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.io.path.name

enum class Platform {
  MAC, WINDOWS, LINUX;

  fun join(first: String, vararg more: String): String = (listOf(first) + more).joinToString(if (this == WINDOWS) "\\" else "/")

  companion object {
    fun current(): Platform = when {
      SystemInfo.isMac -> MAC
      SystemInfo.isWindows -> WINDOWS
      else -> LINUX
    }
  }
}

/** The first app release that serves everything this plugin needs. Its pre-releases do not pass. */
const val MIN_APP_VERSION = "1.19.0"

const val TEST_USER_DATA_DIR_PROPERTY = "dynotable.test.userDataDir"
const val TEST_APP_ROOT_PROPERTY = "dynotable.test.appRoot"
const val TEST_MIN_APP_VERSION_PROPERTY = "dynotable.test.minAppVersion"

/**
 * The test seams. Outside test mode they are never read, so an inherited property cannot point an
 * installed plugin at another app or another port file.
 */
data class Overrides(
  /** Replaces the port-file directory, but keeps the launcher on (the `userDataDir` setting turns it off). */
  val testUserDataDir: String?,
  /** Replaces every standard install location. */
  val testAppRoot: String?,
  val minAppVersion: String,
) {
  companion object {
    fun of(testMode: Boolean, property: (String) -> String?): Overrides {
      if (!testMode) return Overrides(null, null, MIN_APP_VERSION)
      fun read(name: String) = property(name)?.ifEmpty { null }
      return Overrides(
        read(TEST_USER_DATA_DIR_PROPERTY),
        read(TEST_APP_ROOT_PROPERTY),
        read(TEST_MIN_APP_VERSION_PROPERTY) ?: MIN_APP_VERSION,
      )
    }
  }
}

/** File reads by absolute path, in the path form of the target platform. */
interface Fs {
  /** `null` when the file is missing or cannot be read. */
  fun readText(path: String): String?

  /** The entry names, or `null` when the directory is missing. */
  fun list(path: String): List<String>?

  fun isFile(path: String): Boolean

  /** `java.nio`, never VFS: the port file changes outside the IDE, and VFS would serve a cached copy. */
  object Local : Fs {
    override fun readText(path: String): String? = io { Files.readString(Path.of(path)) }

    override fun list(path: String): List<String>? = io { Files.list(Path.of(path)).use { s -> s.map { it.name }.toList() } }

    override fun isFile(path: String): Boolean = io { Files.isRegularFile(Path.of(path)) } ?: false

    private fun <T> io(read: () -> T): T? = try {
      read()
    } catch (_: IOException) {
      null
    } catch (_: InvalidPathException) {
      null
    }
  }
}

fun defaultUserDataDir(platform: Platform, env: Map<String, String>, home: String): String = when (platform) {
  Platform.MAC -> platform.join(home, "Library", "Application Support", "DynoTable")
  Platform.WINDOWS -> platform.join(env["APPDATA"]?.ifEmpty { null } ?: platform.join(home, "AppData", "Roaming"), "DynoTable")
  Platform.LINUX -> platform.join(env["XDG_CONFIG_HOME"]?.ifEmpty { null } ?: platform.join(home, ".config"), "DynoTable")
}

/** [userDataDir] is the setting, empty when unset. */
fun portFilePath(platform: Platform, env: Map<String, String>, home: String, userDataDir: String, overrides: Overrides): String =
  platform.join(overrides.testUserDataDir ?: userDataDir.ifEmpty { null } ?: defaultUserDataDir(platform, env, home), "lsp-port.json")

/** `null` when the file is missing, or not a port file yet (the app may be in the middle of its write). */
fun readPortFile(fs: Fs, path: String): PortFile? {
  val text = fs.readText(path) ?: return null
  return try {
    PortFile.from(JsonParser.parseString(text))
  } catch (_: JsonParseException) {
    null
  } catch (_: ProtocolException) {
    null
  }
}

/** A running process. [user] is `null` when the OS does not tell. */
data class LiveProcess(val user: String?)

fun liveProcess(pid: Long): LiveProcess? =
  ProcessHandle.of(pid).filter { it.isAlive }.map { LiveProcess(it.info().user().orElse(null)) }.orElse(null)

/**
 * A dead `pid` means a crash left the file. Another user's `pid` (or one whose user the OS hides)
 * means the port may now be that user's listener, which must not get the token. Windows gives no
 * reliable owner, so there only a dead `pid` is stale. A file with no `pid` (an older app) is not.
 */
fun isStale(
  file: PortFile,
  platform: Platform = Platform.current(),
  ownUser: String? = ProcessHandle.current().info().user().orElse(null),
  process: (Long) -> LiveProcess? = ::liveProcess,
): Boolean {
  val pid = file.pid ?: return false
  val live = process(pid) ?: return true
  if (platform == Platform.WINDOWS) return false
  return live.user.isNullOrEmpty() || live.user != ownUser
}

fun meetsMinimum(version: String?, minimum: String): Boolean {
  val actual = version?.let(SemVer::parseFromText) ?: return false
  val floor = SemVer.parseFromText(minimum) ?: return false
  return actual >= floor
}
