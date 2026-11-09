package com.dynotable.connect

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

// The app outlives the IDE: a terminal's AWS credentials or Node switches would stay in it for its lifetime.
class LaunchEnvTest {
  @Test
  fun `macOS and Linux keep the desktop allow-list only`() {
    val env = launchEnv(
      Platform.MAC,
      mapOf(
        "ELECTRON_RUN_AS_NODE" to "1",
        "AWS_PROFILE" to "prod",
        "NODE_OPTIONS" to "--inspect",
        "NODE_ENV" to "development",
        "HOME" to "/Users/u",
        "PATH" to "/usr/bin",
        "XAUTHORITY" to "/x",
        "LC_ALL" to "C",
      ),
    )
    assertEquals(mapOf("HOME" to "/Users/u", "PATH" to "/usr/bin", "XAUTHORITY" to "/x", "LC_ALL" to "C"), env)
  }

  @Test
  fun `Windows drops the deny-list and ignores case`() {
    val env = launchEnv(
      Platform.WINDOWS,
      mapOf(
        "Path" to "C:\\Windows",
        "electron_run_as_node" to "1",
        "Node_Options" to "--inspect",
        "AWS_ENDPOINT_URL" to "http://localhost:8000",
      ),
    )
    assertEquals(mapOf("Path" to "C:\\Windows"), env)
  }
}
