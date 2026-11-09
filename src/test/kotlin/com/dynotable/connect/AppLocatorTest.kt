package com.dynotable.connect

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class AppLocatorTest {
  private val env = mapOf("LOCALAPPDATA" to "C:\\L")

  private fun squirrel(folders: List<String>, extra: Map<String, String> = emptyMap()) = FakeFs(
    mapOf("C:\\L\\dynotable\\dynotable.exe" to "") + folders.associate { "C:\\L\\dynotable\\$it\\dynotable.exe" to "" } + extra,
    "\\",
  )

  private fun windows(fs: Fs, appPath: String = "") = locateApp(fs, Platform.WINDOWS, env, "C:\\U", appPath, null)

  // A string sort would pick 1.9.0 and launch an old app.
  @Test
  fun `picks app-1_10_0 over app-1_9_0`() {
    assertEquals(
      AppLocation("C:\\L\\dynotable\\app-1.10.0\\dynotable.exe", version = "1.10.0"),
      windows(squirrel(listOf("app-1.9.0", "app-1.10.0"))),
    )
  }

  @Test
  fun `maps app-1_19_0-beta1 back to 1_19_0-beta_1`() {
    assertEquals("1.19.0-beta.1", windows(squirrel(listOf("app-1.19.0-beta1")))?.version)
  }

  // Squirrel leaves a half-installed or cleaned-up folder behind during an update.
  @Test
  fun `skips a folder without an executable`() {
    val fs = squirrel(listOf("app-1.19.0"), mapOf("C:\\L\\dynotable\\app-1.20.0\\Update.log" to ""))
    assertEquals("1.19.0", windows(fs)?.version)
  }

  // The stub does not pass on its child's exit code, so the launcher would never see exit 3.
  @Test
  fun `resolves a Windows stub app path to its newest app-*`() {
    val fs = squirrel(listOf("app-1.19.0", "app-1.20.0"))
    assertEquals("C:\\L\\dynotable\\app-1.20.0\\dynotable.exe", windows(fs, "C:\\L\\dynotable\\dynotable.exe")?.binary)
  }

  // Else an old app, found through its exe path, would have no version and open its full window.
  @Test
  fun `keeps the app-* folder version of a Windows app path to its dynotable_exe`() {
    val appPath = "C:\\L\\dynotable\\app-1.18.0\\dynotable.exe"
    assertEquals(AppLocation(appPath, version = "1.18.0"), windows(squirrel(listOf("app-1.18.0")), appPath))
  }

  // A path to the bundle's executable must not lose the version, or an old app opens its window.
  @ParameterizedTest
  @ValueSource(strings = ["/Apps/DynoTable.app", "/Apps/DynoTable.app/Contents/MacOS/DynoTable"])
  fun `resolves a macOS app path to the bundle binary and plist version`(appPath: String) {
    val fs = FakeFs(
      mapOf(
        "/Apps/DynoTable.app/Contents/MacOS/DynoTable" to "",
        "/Apps/DynoTable.app/Contents/Info.plist" to "<dict><key>CFBundleShortVersionString</key>\n\t<string>1.19.0</string></dict>",
      ),
    )
    assertEquals(
      AppLocation("/Apps/DynoTable.app/Contents/MacOS/DynoTable", "/Apps/DynoTable.app", "1.19.0"),
      locateApp(fs, Platform.MAC, emptyMap(), "/U", appPath, null),
    )
  }

  // Tests must never find the installed app.
  @Test
  fun `the test app root replaces every standard location`() {
    val fs = FakeFs(mapOf("/Applications/DynoTable.app/Contents/MacOS/DynoTable" to "", "/usr/bin/dynotable" to ""))
    assertNull(locateApp(fs, Platform.MAC, emptyMap(), "/U", "", "/t"))
    assertNull(locateApp(fs, Platform.LINUX, emptyMap(), "/U", "", "/t"))
    assertEquals(AppLocation("/t/dynotable"), locateApp(FakeFs(mapOf("/t/dynotable" to "")), Platform.LINUX, emptyMap(), "/U", "", "/t"))
  }
}
