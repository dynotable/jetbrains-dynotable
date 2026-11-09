package com.dynotable

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.copyToRecursively
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Runs `scripts/drift-check.sh` on a copy of this repository against a local upstream repository
 * built from the same files, so no network is used.
 */
@DisabledOnOs(OS.WINDOWS)
class DriftCheckTest {
  private val projectDir: Path = Path.of(System.getProperty("dynotable.projectDir"))
  private val bundle = "src/main/resources/textmate/dynotable/syntaxes"

  @TempDir lateinit var tmp: Path
  private lateinit var local: Path
  private lateinit var upstream: Path

  @BeforeEach
  fun setUp() {
    local = tmp.resolve("local")
    for (path in listOf("PROTOCOL.md", "contract", bundle, "scripts/drift-check.sh")) {
      copy(projectDir.resolve(path), local.resolve(path))
    }
    upstream = tmp.resolve("upstream")
    copy(projectDir.resolve("PROTOCOL.md"), upstream.resolve("PROTOCOL.md"))
    copy(projectDir.resolve("contract"), upstream.resolve("contract"))
    copy(projectDir.resolve(bundle), upstream.resolve("syntaxes"))
    git("init", "--quiet", "--initial-branch=main")
    commit()
  }

  @Test
  fun `a clean copy passes`() {
    val (exit, output) = driftCheck()
    assertEquals(0, exit, output)
  }

  @Test
  fun `a changed local fixture fails and names the file`() {
    local.resolve("contract/initialize.json").writeText("{}\n")
    val (exit, output) = driftCheck()
    assertEquals(1, exit, output)
    assertTrue("differs: contract/initialize.json" in output, output)
  }

  // A new upstream fixture must reach this repository, so a listing of the local files alone is not enough.
  @Test
  fun `an extra upstream fixture fails and names the file`() {
    upstream.resolve("contract/new-fixture.json").writeText("{}\n")
    commit()
    val (exit, output) = driftCheck()
    assertEquals(1, exit, output)
    assertTrue("missing: contract/new-fixture.json" in output, output)
  }

  // A fixture made up here would test the plugin against an answer the app never gives.
  @Test
  fun `a local fixture that upstream lacks fails and names the file`() {
    local.resolve("contract/local-only.json").writeText("{}\n")
    val (exit, output) = driftCheck()
    assertEquals(1, exit, output)
    assertTrue("extra: contract/local-only.json" in output, output)
  }

  @OptIn(ExperimentalPathApi::class)
  private fun copy(from: Path, to: Path) {
    to.parent.createDirectories()
    if (Files.isDirectory(from)) from.copyToRecursively(to, followLinks = false) else Files.copy(from, to)
  }

  private fun commit() {
    git("add", "-A")
    git("-c", "user.name=test", "-c", "user.email=test@example.com", "-c", "commit.gpgsign=false",
      "commit", "--quiet", "--no-verify", "-m", "fixtures")
  }

  private fun git(vararg args: String) {
    val (exit, output) = run(upstream, listOf("git", *args))
    check(exit == 0) { "git ${args.joinToString(" ")}: $output" }
  }

  private fun driftCheck(): Pair<Int, String> =
    run(local, listOf("bash", "scripts/drift-check.sh"), "DRIFT_UPSTREAM" to upstream.toUri().toString())

  private fun run(dir: Path, command: List<String>, vararg env: Pair<String, String>): Pair<Int, String> {
    val process = ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true)
      .apply { environment().putAll(env) }.start()
    val output = process.inputStream.bufferedReader().readText()
    return process.waitFor() to output
  }
}
