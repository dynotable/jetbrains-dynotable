package com.dynotable.connect

import com.intellij.util.text.SemVer

data class AppLocation(
  /** The executable to spawn. */
  val binary: String,
  /** The `.app` bundle on macOS, for `open -a`. */
  val bundle: String? = null,
  /** `null` where it cannot be read before the launch (Linux). */
  val version: String? = null,
)

/**
 * Finds the app. [appPath] is the setting, empty when unset; it wins. In test mode [testAppRoot]
 * replaces every standard install location.
 */
fun locateApp(fs: Fs, platform: Platform, env: Map<String, String>, home: String, appPath: String, testAppRoot: String?): AppLocation? {
  if (appPath.isNotEmpty()) return fromAppPath(fs, platform, appPath)
  return when (platform) {
    Platform.MAC -> {
      val bundles = if (testAppRoot != null) {
        listOf(platform.join(testAppRoot, "DynoTable.app"))
      } else {
        listOf("/Applications/DynoTable.app", platform.join(home, "Applications", "DynoTable.app"))
      }
      bundles.firstNotNullOfOrNull { macBundle(fs, it) }
    }
    Platform.WINDOWS -> {
      val local = env["LOCALAPPDATA"]?.ifEmpty { null } ?: platform.join(home, "AppData", "Local")
      squirrelRoot(fs, testAppRoot ?: platform.join(local, "dynotable"))
    }
    Platform.LINUX -> {
      val binary = if (testAppRoot != null) platform.join(testAppRoot, "dynotable") else "/usr/bin/dynotable"
      if (fs.isFile(binary)) AppLocation(binary) else null
    }
  }
}

private val APP_FOLDER = Regex("""^app-(\d+\.\d+\.\d+)(?:-(.+))?$""")
private val LETTER_DIGIT_EDGE = Regex("""(?<=[A-Za-z])(?=\d)|(?<=\d)(?=[A-Za-z])""")
private val PLIST_VERSION = Regex("""<key>CFBundleShortVersionString</key>\s*<string>([^<]+)</string>""")
private val MAC_APP_PATH = Regex("""^(.+\.app)(?:/Contents/MacOS/[^/]+)?$""")

/** NuGet strips the dots inside a pre-release part: `app-1.19.0-beta1` is `1.19.0-beta.1`. */
private fun versionFromAppFolder(folder: String): String? {
  val match = APP_FOLDER.matchEntire(folder) ?: return null
  val pre = match.groupValues[2].ifEmpty { null }?.replace(LETTER_DIGIT_EDGE, ".")
  val version = if (pre != null) "${match.groupValues[1]}-$pre" else match.groupValues[1]
  return version.takeIf { SemVer.parseFromText(it) != null }
}

private fun macBundle(fs: Fs, bundle: String): AppLocation? {
  val binary = Platform.MAC.join(bundle, "Contents", "MacOS", "DynoTable")
  if (!fs.isFile(binary)) return null
  // An unreadable Info.plist leaves the version unknown.
  val version = fs.readText(Platform.MAC.join(bundle, "Contents", "Info.plist"))?.let { PLIST_VERSION.find(it)?.groupValues?.get(1) }
  return AppLocation(binary, bundle, version)
}

/** The stub `dynotable.exe` does not pass on its child's exit code, so the newest `app-*` runs. */
private fun squirrelRoot(fs: Fs, root: String): AppLocation? = fs.list(root).orEmpty()
  .mapNotNull { folder -> versionFromAppFolder(folder)?.let { AppLocation(Platform.WINDOWS.join(root, folder, "dynotable.exe"), version = it) } }
  // Squirrel leaves a half-installed or cleaned-up folder behind during an update.
  .filter { fs.isFile(it.binary) }
  .maxByOrNull { SemVer.parseFromText(it.version)!! }

private fun fromAppPath(fs: Fs, platform: Platform, appPath: String): AppLocation? {
  // A path to the executable inside a bundle or an `app-*` folder keeps that version, so an old app
  // is still refused before it can open its window.
  if (platform == Platform.MAC) {
    MAC_APP_PATH.matchEntire(appPath)?.let { return macBundle(fs, it.groupValues[1]) }
  }
  if (platform == Platform.WINDOWS) {
    val folder = appPath.replace('/', '\\').substringBeforeLast('\\', "")
    squirrelRoot(fs, folder)?.let { return it }
    val version = versionFromAppFolder(folder.substringAfterLast('\\'))
    if (version != null && fs.isFile(appPath)) return AppLocation(appPath, version = version)
  }
  return if (fs.isFile(appPath)) AppLocation(appPath) else null
}
