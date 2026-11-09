package com.dynotable.connect

import com.dynotable.protocol.PortFile
import com.dynotable.status.Reason
import com.dynotable.status.Status
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

private val STALE = PortFile(41120, "old", 111)
private val FRESH = PortFile(41121, "new", 222)
private val OK = PreflightOutcome.Ok("1.19.0")

/**
 * The world of one machine. The platform's clients are simulated: a started client is listed at
 * once and reports nothing until the test calls [initialize] or [stop]; a stop unlists it.
 */
private class World(
  var file: PortFile? = FRESH,
  var trusted: Boolean = true,
  /** Answers each preflight; `null` leaves it pending. */
  var answer: (n: Int) -> PreflightOutcome? = { OK },
  var launch: suspend (stale: PortFile?) -> LaunchResult = { awaitCancellation() },
  var listing: Reason? = null,
  var lists: Boolean = true,
  var modal: Boolean = false,
) : ConnectSeams {
  var failNextStatus = false
  val events = mutableListOf<String>()
  val stale = mutableSetOf<PortFile>()
  val listed = mutableSetOf<Int>()
  var preflights = 0
  lateinit var machine: ConnectMachine

  override fun trusted() = trusted

  override fun readPortFile() = file

  override fun isStale(file: PortFile) = file in stale

  override suspend fun launch(stale: PortFile?): LaunchResult {
    events += "launch"
    return launch.invoke(stale)
  }

  override suspend fun preflight(file: PortFile): PreflightOutcome {
    events += "preflight ${file.port}"
    return answer(++preflights) ?: awaitCancellation()
  }

  override suspend fun stopClients() {
    events += "stop"
    listed.clear()
  }

  override fun startClient(attempt: Int, file: PortFile) {
    events += "start $attempt"
    if (lists) listed += attempt
  }

  override fun isListed(attempt: Int) = attempt in listed

  override suspend fun modal() = modal

  override suspend fun connectTimeListing(attempt: Int) = listing

  override fun onStatus(status: Status) {
    if (failNextStatus) {
      failNextStatus = false
      error("paint failed")
    }
  }

  fun initialize(attempt: Int) = machine.serverInitialized(attempt)

  fun stop(attempt: Int, normal: Boolean) = machine.serverStopped(attempt, normal)
}

class ConnectMachineTest {
  private fun TestScope.machine(world: World): ConnectMachine {
    val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
    backgroundScope.launch {
      try {
        awaitCancellation()
      } finally {
        scope.cancel()
      }
    }
    return ConnectMachine(world, scope, "1.19.0").also { world.machine = it }
  }

  /** Connects attempt [attempt] of [world]: the platform client initializes as soon as it starts. */
  private fun TestScope.connect(world: World, machine: ConnectMachine, attempt: Int = 1) {
    machine.trigger()
    runCurrent()
    advanceTimeBy(100)
    world.initialize(attempt)
    runCurrent()
    assertEquals(Status.Connected(attempt), machine.status)
  }

  private fun latched(reason: Reason) = Status.Disconnected(reason)

  @Test
  fun `a stale pid launches the app and opens no socket to the stale port`() = runTest {
    val world = World(file = STALE, launch = { LaunchResult.Ready(FRESH) }).apply { stale += STALE }
    val machine = machine(world)
    connect(world, machine)
    assertEquals(listOf("launch", "preflight 41121", "stop", "start 1"), world.events)
  }

  @Test
  fun `ECONNREFUSED launches, and a second ECONNREFUSED after the launch latches start-failed`() = runTest {
    val world = World(file = STALE, launch = { LaunchResult.Ready(FRESH) }, answer = { PreflightOutcome.Stale })
    val machine = machine(world)
    machine.ensureConnected()
    runCurrent()
    assertEquals(listOf("preflight 41120", "launch", "preflight 41121", "stop"), world.events)
    assertEquals(latched(Reason.START_FAILED), machine.status)
  }

  // The user-data dir does not exist until the launched app creates it.
  @Test
  fun `no port file launches, and the launched app's file connects`() = runTest {
    val world = World(file = null, launch = { LaunchResult.Ready(FRESH) })
    val machine = machine(world)
    connect(world, machine)
    assertEquals("launch", world.events.first())
  }

  @Test
  fun `a launch failure latches its reason`() = runTest {
    val world = World(file = null, launch = { LaunchResult.Failed(Reason.INTEGRATION_OFF) })
    val machine = machine(world)
    machine.ensureConnected()
    assertEquals(latched(Reason.INTEGRATION_OFF), machine.status)
  }

  @Test
  fun `each preflight outcome latches its reason`() = runTest {
    val cases = mapOf(
      PreflightOutcome.SignedOut to Reason.SIGNED_OUT,
      PreflightOutcome.Refused to Reason.START_FAILED,
      PreflightOutcome.ConnectFailed to Reason.START_FAILED,
      PreflightOutcome.Closed to Reason.CONNECTION_ENDED,
      PreflightOutcome.Ok("1.18.2") to Reason.UPDATE_APP,
      // An app that does not say its version cannot be checked, so it is too old.
      PreflightOutcome.Ok(null) to Reason.UPDATE_APP,
    )
    for ((outcome, reason) in cases) {
      val world = World(answer = { outcome })
      val machine = machine(world)
      machine.ensureConnected()
      assertEquals(latched(reason), machine.status, "$outcome")
      assertFalse(world.events.any { it.startsWith("start") }, "$outcome started a client")
    }
  }

  @Test
  fun `-32898 rereads the port file once, then latches auth-failed`() = runTest {
    val world = World(answer = { PreflightOutcome.AuthFailed })
    val machine = machine(world)
    machine.ensureConnected()
    assertEquals(2, world.preflights)
    assertEquals(latched(Reason.AUTH_FAILED), machine.status)
  }

  // The server's handshake deadline is 30 s; ours must not fire first and hide its answer.
  @Test
  fun `a preflight with no answer times out at 40 s`() = runTest {
    val world = World(answer = { null })
    val machine = machine(world)
    machine.trigger()
    advanceTimeBy(39_999)
    assertEquals(Status.Starting(), machine.status)
    advanceTimeBy(2)
    assertEquals(latched(Reason.TIMED_OUT), machine.status)
  }

  @Test
  fun `concurrent triggers give one preflight`() = runTest {
    val world = World(answer = { null })
    val machine = machine(world)
    machine.trigger()
    machine.trigger()
    runCurrent()
    assertEquals(1, world.preflights)
  }

  @Test
  fun `a client that never shows in the list latches start-failed after 10 s, not counting modal time`() = runTest {
    val world = World(lists = false, modal = true)
    val machine = machine(world)
    machine.trigger()
    advanceTimeBy(60_000)
    assertEquals(Status.Starting(), machine.status)
    world.modal = false
    advanceTimeBy(10_100)
    assertEquals(latched(Reason.START_FAILED), machine.status)
    assertEquals("stop", world.events.last())
  }

  @Test
  fun `a listed client that never reports latches timed-out after 40 s and is stopped`() = runTest {
    val world = World()
    val machine = machine(world)
    machine.trigger()
    advanceTimeBy(40_000)
    assertEquals(Status.Starting(), machine.status)
    advanceTimeBy(200)
    assertEquals(latched(Reason.TIMED_OUT), machine.status)
    assertEquals("stop", world.events.last())
  }

  // The preflight passed, then the platform's own initialize failed (a token rotated in between).
  @Test
  fun `a stop before serverInitialized latches start-failed`() = runTest {
    val world = World()
    val machine = machine(world)
    machine.trigger()
    advanceTimeBy(100)
    world.stop(1, normal = false)
    runCurrent()
    assertEquals(latched(Reason.START_FAILED), machine.status)
  }

  // An app the user quit must never come back invisibly.
  @Test
  fun `an unexpected stop latches connection-ended, and the next trigger starts nothing`() = runTest {
    val world = World()
    val machine = machine(world)
    connect(world, machine)
    world.stop(1, normal = false)
    runCurrent()
    assertEquals(latched(Reason.CONNECTION_ENDED), machine.status)
    machine.ensureConnected()
    advanceTimeBy(60_000)
    assertEquals(1, world.preflights)
  }

  @Test
  fun `a platform Restart connects again on the same descriptor, and a platform Stop waits for the next trigger`() = runTest {
    val world = World()
    val machine = machine(world)
    connect(world, machine)
    world.stop(1, normal = true)
    runCurrent()
    assertEquals(Status.Disconnected(Reason.STOPPED), machine.status)
    world.initialize(1)
    runCurrent()
    assertEquals(Status.Connected(1), machine.status)
    assertEquals(1, world.preflights)

    world.stop(1, normal = true)
    runCurrent()
    advanceTimeBy(60_000)
    assertEquals(Status.Disconnected(Reason.STOPPED), machine.status)
    connect(world, machine, attempt = 2)
  }

  @Test
  fun `while closing, a stop and an initialize change nothing`() = runTest {
    val world = World()
    val machine = machine(world)
    connect(world, machine)
    machine.close()
    world.stop(1, normal = false)
    world.initialize(1)
    machine.trigger()
    runCurrent()
    assertEquals(Status.Connected(1), machine.status)
    assertEquals(1, world.preflights)
  }

  // A test teardown or a project close stops the listed clients next; an attempt still waiting then works during that stop.
  @Test
  fun `a close during a start ends the attempt at once and latches nothing`() = runTest {
    val world = World()
    val machine = machine(world)
    machine.trigger()
    advanceTimeBy(100)
    assertEquals(listOf("preflight 41121", "stop", "start 1"), world.events)
    machine.close()
    val idle = async { machine.awaitIdle() }
    runCurrent()
    assertTrue(idle.isCompleted)
    assertEquals(Status.Starting(), machine.status)
  }

  @Test
  fun `a restart during a start waits for it, then ends it before the next start`() = runTest {
    val world = World()
    val machine = machine(world)
    machine.trigger()
    advanceTimeBy(100)
    val restarted = async { machine.restart() }
    runCurrent()
    assertEquals(listOf("preflight 41121", "stop", "start 1"), world.events)
    world.initialize(1)
    advanceTimeBy(100)
    world.initialize(2)
    restarted.await()
    assertEquals(listOf("preflight 41121", "stop", "start 1", "stop", "preflight 41121", "stop", "start 2"), world.events)
    assertEquals(Status.Connected(2), machine.status)
  }

  // Repeated Reconnect clicks, or settings changes during a start, would each replace the session the one before made.
  @Test
  fun `a restart storm shares the queued restart and leaves one session on the newest descriptor`() = runTest {
    val world = World()
    val machine = machine(world)
    machine.trigger()
    advanceTimeBy(100)
    val first = async { machine.restart() }
    runCurrent()
    // The first waits for the start in flight; the next three share one queued restart.
    val more = List(3) { async { machine.restart() } }
    runCurrent()
    world.initialize(1)
    advanceTimeBy(100)
    world.initialize(2)
    advanceTimeBy(100)
    world.initialize(3)
    (more + first).forEach { it.await() }
    assertEquals(listOf("start 1", "start 2", "start 3"), world.events.filter { it.startsWith("start") })
    assertEquals(Status.Connected(3), machine.status)
  }

  @Test
  fun `a failed restart does not block the next`() = runTest {
    val world = World()
    val machine = machine(world)
    connect(world, machine)
    world.failNextStatus = true
    machine.restart()
    val next = async { machine.restart() }
    advanceTimeBy(100)
    world.initialize(2)
    next.await()
    assertEquals(Status.Connected(2), machine.status)
  }

  @Test
  fun `a plugin restart waits for a running query, and a latch fails it at once`() = runTest {
    val world = World()
    val machine = machine(world)
    connect(world, machine)
    val finish = CompletableDeferred<String>()
    val run = async { machine.trackRun { finish.await() } }
    runCurrent()
    val restarted = async { machine.restart() }
    runCurrent()
    assertEquals(Status.Starting(waitingForRun = true), machine.status)
    finish.complete("rows")
    assertEquals("rows", run.await())
    advanceTimeBy(100)
    world.initialize(2)
    restarted.await()

    val held = async { runCatching { machine.trackRun { awaitCancellation() } } }
    runCurrent()
    machine.latch(2, Reason.MISSING_REQUEST)
    assertTrue(held.await().exceptionOrNull() is SessionEnded)
  }

  // A Validate that waits on a slow index must not hold a profile change; its stopped client never answers it.
  @Test
  fun `a restart does not wait for a command that holds no restart, and fails it at once`() = runTest {
    val world = World()
    val machine = machine(world)
    connect(world, machine)
    val command = async { runCatching { machine.trackRun(holdsRestart = false) { awaitCancellation() } } }
    runCurrent()
    val restarted = async { machine.restart() }
    runCurrent()
    assertEquals(Status.Starting(), machine.status)
    assertTrue(command.await().exceptionOrNull() is SessionEnded)
    advanceTimeBy(100)
    world.initialize(2)
    restarted.await()
  }

  @Test
  fun `a stop of the current session fails its run, and a late stop of a retired one neither latches nor fails a run`() = runTest {
    val world = World()
    val machine = machine(world)
    connect(world, machine)
    val first = async { runCatching { machine.trackRun { awaitCancellation() } } }
    runCurrent()
    world.stop(1, normal = true)
    runCurrent()
    assertTrue(first.await().exceptionOrNull() is SessionEnded)

    connect(world, machine, attempt = 2)
    val second = async { runCatching { machine.trackRun { awaitCancellation() } } }
    runCurrent()
    world.stop(1, normal = false)
    runCurrent()
    assertEquals(Status.Connected(2), machine.status)
    assertTrue(second.isActive)
    second.cancel()
  }

  // The connect-time listing of a replaced session answers late; it must not end the new one.
  @Test
  fun `a latch from an older attempt is ignored, and a latch is not replaced by connection-ended when its own stop arrives`() = runTest {
    val world = World()
    val machine = machine(world)
    connect(world, machine)
    val restarted = async { machine.restart() }
    advanceTimeBy(100)
    world.initialize(2)
    restarted.await()
    machine.latch(1, Reason.MISSING_REQUEST)
    assertEquals(Status.Connected(2), machine.status)
    machine.latch(2, Reason.MISSING_REQUEST)
    world.stop(2, normal = false)
    runCurrent()
    assertEquals(latched(Reason.MISSING_REQUEST), machine.status)
  }

  @Test
  fun `the connect-time listing sets unbound or latches missing-request`() = runTest {
    val unbound = World(listing = Reason.UNBOUND)
    val first = machine(unbound)
    first.trigger()
    advanceTimeBy(100)
    unbound.initialize(1)
    runCurrent()
    assertEquals(Status.Unbound(1), first.status)

    val missing = World(listing = Reason.MISSING_REQUEST)
    val second = machine(missing)
    second.trigger()
    advanceTimeBy(100)
    missing.initialize(1)
    runCurrent()
    assertEquals(latched(Reason.MISSING_REQUEST), second.status)
  }

  @Test
  fun `a serverInitialized from an old descriptor during an attempt stops nothing, but while latched it stops the client`() = runTest {
    val world = World()
    val machine = machine(world)
    connect(world, machine)
    val restarted = async { machine.restart() }
    advanceTimeBy(100)
    val stops = world.events.count { it == "stop" }
    world.initialize(1)
    runCurrent()
    assertEquals(stops, world.events.count { it == "stop" })
    world.initialize(2)
    restarted.await()

    machine.latch(2, Reason.SIGNED_OUT)
    runCurrent()
    val latchedStops = world.events.count { it == "stop" }
    // The platform's Restart of a latched client.
    world.initialize(2)
    runCurrent()
    assertEquals(latchedStops + 1, world.events.count { it == "stop" })
    assertEquals(latched(Reason.SIGNED_OUT), machine.status)
  }

  @Test
  fun `a watcher event for the in-flight key is ignored, a delete does nothing, and a new key reconnects`() = runTest {
    val world = World(answer = { if (it == 1) null else OK })
    val machine = machine(world)
    machine.trigger()
    runCurrent()
    machine.portFileChanged()
    advanceTimeBy(50_000)
    assertEquals(latched(Reason.TIMED_OUT), machine.status)
    assertEquals(1, world.preflights)

    world.file = null
    machine.portFileChanged()
    runCurrent()
    assertEquals(1, world.preflights)

    world.file = STALE
    machine.portFileChanged()
    advanceTimeBy(100)
    world.initialize(2)
    runCurrent()
    assertEquals(Status.Connected(2), machine.status)
  }

  // The app restarted during the attempt: its new port file must not wait for a Reconnect.
  @Test
  fun `a new key written during an attempt reconnects once the attempt latches`() = runTest {
    val world = World(answer = { if (it == 1) PreflightOutcome.SignedOut else OK })
    val launch = CompletableDeferred<LaunchResult>()
    world.launch = { launch.await() }
    world.file = null
    val machine = machine(world)
    machine.trigger()
    runCurrent()
    world.file = STALE
    machine.portFileChanged()
    launch.complete(LaunchResult.Ready(FRESH))
    runCurrent()
    assertEquals(listOf("launch", "preflight 41121", "preflight 41120"), world.events.filter { !it.startsWith("st") })
  }

  @Test
  fun `a watcher event for an unchanged dead-pid file after a failed launch does not relaunch`() = runTest {
    val world = World(file = STALE, launch = { LaunchResult.Failed(Reason.START_FAILED) }).apply { stale += STALE }
    val machine = machine(world)
    machine.ensureConnected()
    machine.portFileChanged()
    runCurrent()
    assertEquals(1, world.events.count { it == "launch" })
  }

  @Test
  fun `an untrusted project opens no preflight and launches nothing until trusted`() = runTest {
    val world = World(trusted = false)
    val machine = machine(world)
    machine.ensureConnected()
    assertEquals(Status.Disconnected(Reason.UNTRUSTED), machine.status)
    assertEquals(emptyList<String>(), world.events)
    world.trusted = true
    connect(world, machine)
  }

  @Test
  fun `a run outside a session fails at once`() = runTest {
    val machine = machine(World())
    assertThrows<SessionEnded> { machine.trackRun { "rows" } }
  }
}
