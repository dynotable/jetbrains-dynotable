package com.dynotable.connect

private val ALLOWED = setOf(
  "PATH", "HOME", "USER", "LOGNAME", "SHELL", "LANG", "TMPDIR", "DISPLAY", "WAYLAND_DISPLAY", "XAUTHORITY",
  "DBUS_SESSION_BUS_ADDRESS", "DESKTOP_SESSION", "XMODIFIERS",
)

// The desktop-session prefixes choose Chromium's secret-store backend and the input method.
private val ALLOWED_PREFIXES = listOf("LC_", "XDG_", "KDE_", "GNOME_", "GTK_", "QT_", "GDK_")

// `AWS_ENDPOINT_URL*` would send every profile's calls to a local emulator for the app's lifetime.
private val DENIED_PREFIXES = listOf("ELECTRON_", "NODE_", "NPM_", "VSCODE_", "AWS_")

/**
 * The environment of a `--headless` launch, close to a desktop launch. The long-lived app must not
 * inherit a terminal's AWS credentials, Node or Electron switches, or development variables.
 */
fun launchEnv(platform: Platform, inherited: Map<String, String>): Map<String, String> =
  if (platform == Platform.WINDOWS) {
    // Windows names ignore case: `Path` is PATH, and `electron_run_as_node` is denied too.
    inherited.filterKeys { name -> DENIED_PREFIXES.none { name.uppercase().startsWith(it) } }
  } else {
    inherited.filterKeys { name -> name in ALLOWED || ALLOWED_PREFIXES.any { name.startsWith(it) } }
  }
