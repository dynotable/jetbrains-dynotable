package com.dynotable.live

import com.dynotable.connect.DETACH
import com.dynotable.connect.Platform
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile

/** The guards that keep a live run off the real app and its data. Each form below once reached, or could reach, the real directory. */
@DisabledOnOs(OS.WINDOWS)
class LiveAppTest {
  @TempDir
  lateinit var temp: Path

  private val home get() = temp.resolve("home")
  private val real get() = home.resolve("Library/Application Support/DynoTable")

  private fun dirOf(live: String, deny: String? = null) =
    liveUserDataDir(mapOf("DYNOTABLE_LIVE_USER_DATA_DIR" to live) + listOfNotNull(deny?.let { "DYNOTABLE_LIVE_DENY_USER_DATA_DIR" to it }), home.toString(), Platform.MAC)

  @Test
  fun `a test directory passes`() {
    real.createDirectories()
    assertEquals(temp.resolve("live").toString(), dirOf("${temp.resolve("live")}/"))
  }

  @Test
  fun `the real data directory is refused in every spelling`() {
    real.createDirectories()
    val link = temp.resolve("link")
    Files.createSymbolicLink(link, real)
    for (form in listOf("$real", "$real/", "${real.parent}/x/../DynoTable", "$link", "${real.toString().uppercase()}", "$real/sub", "$home")) {
      assertThrows<IllegalArgumentException>(form) { dirOf(form) }
    }
  }

  @Test
  fun `the deny directory is refused, also through a link`() {
    val deny = temp.resolve("dev-data").createDirectories()
    val link = temp.resolve("dev-link")
    Files.createSymbolicLink(link, deny)
    assertThrows<IllegalArgumentException> { dirOf("$link", deny = "$deny") }
  }

  @Test
  fun `a relative directory is refused`() {
    assertThrows<IllegalArgumentException> { dirOf("live") }
  }

  // Open DynoTable (`open -a`, or the binary with no flag) would start an app with its window, outside the test's control.
  @Test
  fun `the live spawner starts only the wrapper with --headless, directly or through the detach wrapper`() {
    val wrapper = temp.resolve("dynotable-dev")
    Files.writeString(wrapper, "#!/bin/sh\necho started >> '${temp.resolve("starts")}'\n")
    Files.setPosixFilePermissions(wrapper, PosixFilePermissions.fromString("rwx------"))
    val other = temp.resolve("other").also { Files.copy(wrapper, it) }
    val guard = guardedSpawner("$wrapper")
    val refused = listOf(
      listOf("/usr/bin/open", "-a", "$wrapper"),
      listOf("$wrapper"),
      listOf("$wrapper", "--headless", "--extra"),
      listOf("$other", "--headless"),
      DETACH + listOf("$other", "--headless"),
    )
    for (command in refused) assertThrows<IllegalStateException>("$command") { guard.start(ProcessBuilder(command)) }
    for (command in listOf(listOf("$wrapper", "--headless"), DETACH + listOf("$wrapper", "--headless"))) guard.start(ProcessBuilder(command)).waitFor()
    assertEquals(listOf("started", "started"), Files.readAllLines(temp.resolve("starts")))
  }

  private fun appOf(path: String) = liveAppPath(mapOf("DYNOTABLE_LIVE_APP_PATH" to path))

  @Test
  fun `a wrapper outside app bundles passes`() {
    val wrapper = temp.resolve("dynotable-dev").createFile()
    assertEquals("$wrapper", appOf("$wrapper"))
  }

  @Test
  fun `a binary in an app bundle, a link to one, a path under Applications and a relative path are refused`() {
    val binary = temp.resolve("DynoTable.app/Contents/MacOS").createDirectories().resolve("DynoTable").createFile()
    val link = temp.resolve("innocent")
    Files.createSymbolicLink(link, binary)
    for (form in listOf("$binary", "$link", "/Applications/DynoTable.app/Contents/MacOS/DynoTable", "/applications/x", "dynotable-dev")) {
      assertThrows<IllegalArgumentException>(form) { appOf(form) }
    }
  }
}
