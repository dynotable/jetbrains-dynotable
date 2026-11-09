package com.dynotable.status

import com.dynotable.status.Action.DOWNLOAD
import com.dynotable.status.Action.OPEN_APP
import com.dynotable.status.Action.OPEN_DOCS
import com.dynotable.status.Action.RECONNECT
import com.dynotable.status.Action.SELECT_PROFILE
import com.dynotable.status.Action.SET_APP_PATH
import com.dynotable.status.Action.TRUST_PROJECT

enum class Action(val label: String) {
  OPEN_APP("Open DynoTable"),
  DOWNLOAD("Download DynoTable"),
  SET_APP_PATH("Set App Path"),
  OPEN_DOCS("Open Documentation"),
  SELECT_PROFILE("Select Profile"),
  RECONNECT("Reconnect"),
  TRUST_PROJECT("Trust Project"),
}

/**
 * Why DynoTable does not serve the project. A [latched] reason holds until the port file changes or
 * the user runs Reconnect. The first action is the likely fix.
 */
enum class Reason(val latched: Boolean, val sentence: String, val actions: List<Action>) {
  INTEGRATION_OFF(
    true,
    "The Editor integration is off in DynoTable. Turn it on in DynoTable under Settings → Editor integration.",
    listOf(OPEN_APP, RECONNECT),
  ),
  START_FAILED(true, "DynoTable could not start the Editor integration.", listOf(OPEN_APP, RECONNECT)),
  CRASHED(true, "DynoTable stopped before the Editor integration was ready.", listOf(OPEN_APP, RECONNECT)),
  SPAWN_FAILED(true, "The plugin could not start DynoTable. Make sure the app path points to the app.", listOf(SET_APP_PATH, RECONNECT)),
  TIMED_OUT(true, "DynoTable did not start the Editor integration in time.", listOf(OPEN_APP, RECONNECT)),
  NOT_SERVING(
    true,
    "DynoTable is running, but the Editor integration is not serving. Open DynoTable and check Settings → Editor integration.",
    listOf(OPEN_APP, RECONNECT),
  ),
  AUTH_FAILED(true, "DynoTable refused the connection. Restart DynoTable, then reconnect.", listOf(OPEN_APP, RECONNECT)),
  SIGNED_OUT(true, "DynoTable is signed out. Sign in to DynoTable, then reconnect.", listOf(OPEN_APP, RECONNECT)),
  UPDATE_APP(true, "This version of DynoTable is too old for the plugin. Update DynoTable.", listOf(DOWNLOAD, RECONNECT)),
  NOT_INSTALLED(true, "DynoTable is not installed. Install it, or set the path of the app.", listOf(DOWNLOAD, RECONNECT, SET_APP_PATH)),
  LAUNCHER_OFF(
    true,
    "A custom DynoTable user data directory is set, so the plugin does not start DynoTable. Start DynoTable with that directory.",
    listOf(OPEN_DOCS, RECONNECT),
  ),
  MISSING_REQUEST(
    true,
    "This DynoTable build does not support a request the plugin needs. Update DynoTable.",
    listOf(DOWNLOAD, RECONNECT),
  ),
  CONNECTION_ENDED(
    true,
    "The connection to DynoTable ended. DynoTable quit or stopped, the Editor integration was turned off, or DynoTable signed out.",
    listOf(OPEN_APP, RECONNECT),
  ),

  /** A state of a live session. */
  UNBOUND(false, "Select a DynoTable profile to run queries and list tables.", listOf(SELECT_PROFILE, RECONNECT)),

  /** Trusting the project connects, so Reconnect has nothing to do. */
  UNTRUSTED(false, "DynoTable does not run in an untrusted project.", listOf(TRUST_PROJECT, OPEN_DOCS)),

  /** The platform's Stop or Restart. The next trigger connects again. */
  STOPPED(false, "The IDE stopped the DynoTable language server.", listOf(RECONNECT)),
}
