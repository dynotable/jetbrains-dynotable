package com.dynotable.connect

import com.dynotable.protocol.PortFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Files
import java.nio.file.Path

/** Files by absolute path. A directory exists when some file sits below it. */
class FakeFs(private val files: Map<String, String>, private val separator: String = "/") : Fs {
  override fun readText(path: String) = files[path]

  override fun list(path: String) = files.keys
    .filter { it.startsWith(path + separator) }
    .map { it.removePrefix(path + separator).substringBefore(separator) }
    .distinct()
    .ifEmpty { null }

  override fun isFile(path: String) = path in files
}

class DiscoveryTest {
  private val seams = mapOf(
    TEST_USER_DATA_DIR_PROPERTY to "/tmp/ud",
    TEST_APP_ROOT_PROPERTY to "/tmp/apps",
    TEST_MIN_APP_VERSION_PROPERTY to "0.0.1",
  )

  // An inherited system property must not point an installed plugin at another app or port file.
  @Test
  fun `outside test mode the seams and the minimum-version override are ignored`() {
    assertEquals(Overrides(null, null, "1.19.0"), Overrides.of(testMode = false, seams::get))
  }

  @Test
  fun `test mode applies the seams`() {
    assertEquals(Overrides("/tmp/ud", "/tmp/apps", "0.0.1"), Overrides.of(testMode = true, seams::get))
  }

  // A test with no harness still creates the project's connection; it must not reach the user's app.
  @Test
  fun `in test mode an unset seam reaches neither the real port file nor an installed app`() {
    val overrides = Overrides.of(testMode = true) { null }
    val path = portFilePath(Platform.MAC, emptyMap(), "/Users/u", "", overrides)
    assertFalse(path.startsWith("/Users/u/"), path)
    val installed = FakeFs(mapOf("/Applications/DynoTable.app/Contents/MacOS/DynoTable" to "", "/usr/bin/dynotable" to ""))
    assertNull(locateApp(installed, Platform.MAC, emptyMap(), "/Users/u", "", overrides.testAppRoot))
    assertNull(locateApp(installed, Platform.LINUX, emptyMap(), "/Users/u", "", overrides.testAppRoot))
  }

  @ParameterizedTest
  @CsvSource(
    "MAC, , , /Users/u/Library/Application Support/DynoTable/lsp-port.json",
    "WINDOWS, APPDATA, D:\\Roaming, D:\\Roaming\\DynoTable\\lsp-port.json",
    "LINUX, XDG_CONFIG_HOME, /cfg, /cfg/DynoTable/lsp-port.json",
    "LINUX, , , /Users/u/.config/DynoTable/lsp-port.json",
  )
  fun `the port file of each OS`(platform: Platform, name: String?, value: String?, expected: String) {
    val env = if (name == null) emptyMap() else mapOf(name to value!!)
    assertEquals(expected, portFilePath(platform, env, "/Users/u", "", Overrides.of(false) { null }))
  }

  // The test dir wins over the setting, so a developer's userDataDir cannot send a test to their own app.
  @Test
  fun `the userDataDir setting replaces the standard dir, and the test dir replaces both`() {
    assertEquals("/ud/lsp-port.json", portFilePath(Platform.MAC, emptyMap(), "/U", "/ud", Overrides(null, null, MIN_APP_VERSION)))
    assertEquals("/t/lsp-port.json", portFilePath(Platform.MAC, emptyMap(), "/U", "/ud", Overrides("/t", null, MIN_APP_VERSION)))
  }

  private fun read(text: String) = readPortFile(FakeFs(mapOf("/p" to text)), "/p")

  // An older app writes no pid; refusing its file would keep the version gate from saying "update".
  @Test
  fun `a file with no pid reads`() {
    assertEquals(PortFile(41120, "t", null), read("""{"port":41120,"token":"t"}"""))
  }

  // The app may be in the middle of its write; the next watcher event reads the file again.
  @ParameterizedTest
  @CsvSource(delimiter = '|', value = ["{\"port\":41120,\"tok", "{\"port\":\"41120\",\"token\":\"t\"}"])
  fun `a truncated file and a string port are not a port file yet`(text: String) {
    assertNull(read(text))
  }

  @Test
  fun `a missing file on disk is no port file, not an error`(@TempDir dir: Path) {
    assertNull(readPortFile(Fs.Local, dir.resolve("lsp-port.json").toString()))
  }

  private val withPid = PortFile(41120, "t", 42)

  // A dead pid means a crash left the file. Another user's or a hidden owner means the stale port may now be
  // another user's listener, which would receive the token. Windows gives no reliable owner.
  @ParameterizedTest
  @CsvSource(
    "MAC, dead, true",
    "LINUX, other, true",
    "MAC, '', true",
    "MAC, hidden, true",
    "MAC, me, false",
    "WINDOWS, other, false",
    "WINDOWS, dead, true",
  )
  fun `stale check`(platform: Platform, owner: String, stale: Boolean) {
    val process = when (owner) {
      "dead" -> null
      "hidden" -> LiveProcess(null)
      else -> LiveProcess(owner)
    }
    assertEquals(stale, isStale(withPid, platform, "me") { process })
  }

  @Test
  fun `a file with no pid is not stale and is not checked`() {
    assertFalse(isStale(PortFile(41120, "t", null), Platform.MAC, "me") { error("must not check") })
  }

  // The fakes above cannot show that ProcessHandle really reports the same owner for the IDE and the app it
  // starts. If it did not, every live app would look stale and the plugin would launch it again and again.
  @Test
  @DisabledOnOs(OS.WINDOWS)
  fun `on this machine an own live process is not stale and an ended one is`() {
    val ended = ProcessBuilder("true").start().also { it.waitFor() }.pid()
    assertFalse(isStale(PortFile(1, "t", ProcessHandle.current().pid())))
    assertTrue(isStale(PortFile(1, "t", ended)))
  }

  @ParameterizedTest
  @CsvSource(
    "1.19.0-beta.1, 1.19.0-0, true",
    "1.20.0-beta.1, 1.19.0-0, true",
    "1.18.2, 1.19.0-0, false",
    "1.19.0-beta.1, 1.19.0, false",
    "1.19.10, 1.19.9, true",
    ", 1.19.0, false",
    // Not semver: an unparsed version must refuse, not throw and leave the attempt with no reason.
    "1.19, 1.19.0, false",
  )
  fun `meetsMinimum`(version: String?, minimum: String, expected: Boolean) {
    assertEquals(expected, meetsMinimum(version, minimum))
  }

  // The locator reads `app-*` folder names, and a missing install dir must mean "not installed", not a throw.
  @Test
  fun `the local fs lists entry names, and a missing directory as null`(@TempDir dir: Path) {
    Files.writeString(dir.resolve("a"), "x")
    assertEquals(listOf("a"), Fs.Local.list(dir.toString()))
    assertNull(Fs.Local.list(dir.resolve("missing").toString()))
  }
}
