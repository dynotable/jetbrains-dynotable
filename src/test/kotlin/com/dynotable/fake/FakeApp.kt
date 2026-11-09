package com.dynotable.fake

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** What one start of the fake does after it records itself. */
sealed interface FakeRun {
  data class Exit(val code: Int) : FakeRun

  /** Stays alive and writes no port file. */
  data object Hang : FakeRun

  /** Writes a port file with its own pid, then stays alive, as a serving app does. */
  data class Serve(val portFile: Path, val port: Int, val token: String) : FakeRun
}

/**
 * A shell script at `<dir>/dynotable` that stands in for the app binary (POSIX only). Start `n`
 * records its argv, environment, physical cwd, pid and process group, then does `runs[n - 1]`.
 */
class FakeApp(dir: Path, runs: List<FakeRun>, executable: Boolean = true) : AutoCloseable {
  val binary: Path = dir.createDirectories().resolve("dynotable")
  private val records = dir.resolve("records").createDirectories()

  init {
    val cases = runs.mapIndexed { i, run -> "${i + 1}) ${script(run)} ;;" }.joinToString("\n")
    binary.writeText(
      """
      |#!/bin/sh
      |r='$records'
      |n=${'$'}(( ${'$'}(/bin/cat "${'$'}r/count" 2>/dev/null || echo 0) + 1 ))
      |for a in "${'$'}@"; do printf '%s\n' "${'$'}a"; done > "${'$'}r/argv-${'$'}n"
      |/usr/bin/env > "${'$'}r/env-${'$'}n"
      |pwd -P > "${'$'}r/cwd-${'$'}n"
      |/bin/ps -o pgid= -p ${'$'}${'$'} > "${'$'}r/pgid-${'$'}n"
      |echo ${'$'}${'$'} > "${'$'}r/pid-${'$'}n"
      |echo ${'$'}n > "${'$'}r/count"
      |case ${'$'}n in
      |$cases
      |esac
      |exit 99
      |""".trimMargin(),
    )
    if (executable) Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rwx------"))
  }

  private fun script(run: FakeRun): String = when (run) {
    is FakeRun.Exit -> "exit ${run.code}"
    FakeRun.Hang -> "exec /bin/sleep 600"
    is FakeRun.Serve -> {
      val file = run.portFile.also { it.parent.createDirectories() }
      "printf '{\"port\":%s,\"token\":\"%s\",\"pid\":%s}' ${run.port} '${run.token}' \$\$ > '$file.tmp' && /bin/mv '$file.tmp' '$file'; exec /bin/sleep 600"
    }
  }

  /** How many times the fake started. */
  val starts: Int get() = records.resolve("count").takeIf { it.exists() }?.readText()?.trim()?.toInt() ?: 0

  fun argv(start: Int): List<String> = records.resolve("argv-$start").readLines()

  fun env(start: Int): Map<String, String> =
    records.resolve("env-$start").readLines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }

  fun cwd(start: Int): String = records.resolve("cwd-$start").readText().trim()

  fun pid(start: Int): Long = records.resolve("pid-$start").readText().trim().toLong()

  fun processGroup(start: Int): Long = records.resolve("pgid-$start").readText().trim().toLong()

  /** Kills the starts that still run. Only the fake's own `sleep`, in case a pid was reused. */
  override fun close() {
    for (start in 1..starts) {
      ProcessHandle.of(pid(start))
        .filter { it.info().command().orElse("").endsWith("sleep") }
        .ifPresent { it.destroyForcibly() }
    }
  }
}
