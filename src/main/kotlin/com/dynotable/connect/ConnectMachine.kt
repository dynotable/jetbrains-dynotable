package com.dynotable.connect

import com.dynotable.protocol.PortFile
import com.dynotable.status.Reason
import com.dynotable.status.Status
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.ContinuationInterceptor

/** What the plugin's own `initialize` over its own socket showed. */
sealed interface PreflightOutcome {
  data class Ok(val version: String?) : PreflightOutcome

  /** `ECONNREFUSED`: a killed app left its port file behind. */
  data object Stale : PreflightOutcome

  /** Another connect error. */
  data object ConnectFailed : PreflightOutcome

  /** `-32898`: the token is wrong. */
  data object AuthFailed : PreflightOutcome

  data object SignedOut : PreflightOutcome

  /** Another `initialize` error. */
  data object Refused : PreflightOutcome

  /** The server closed the socket before it answered. */
  data object Closed : PreflightOutcome
}

/** The world of the [ConnectMachine]: the app, the plugin's own socket and the platform's LSP clients. */
interface ConnectSeams {
  fun trusted(): Boolean

  fun readPortFile(): PortFile?

  fun isStale(file: PortFile): Boolean

  suspend fun launch(stale: PortFile?): LaunchResult

  /** Cancellable: a cancel closes the socket. */
  suspend fun preflight(file: PortFile): PreflightOutcome

  /** Stops every client of the plugin. It can block for seconds. */
  suspend fun stopClients()

  /** Asks the platform to start a client with a new descriptor for [attempt]. Must not block. */
  fun startClient(attempt: Int, file: PortFile)

  /** True while the client of [attempt]'s descriptor is in the platform's client list. */
  fun isListed(attempt: Int): Boolean

  /** True while a modal dialog is open: the platform does not start a client then. */
  suspend fun modal(): Boolean

  /** The connect-time `dynotable/listTables`: [Reason.UNBOUND], [Reason.MISSING_REQUEST] or `null`. */
  suspend fun connectTimeListing(attempt: Int): Reason?

  fun onStatus(status: Status)
}

/** The session of a run ended before its answer: a latch, a stop or a retired descriptor. */
class SessionEnded : Exception("The DynoTable session ended.")

/**
 * The connection rules. One attempt at a time: preflight, launch, then a platform client with a new
 * descriptor. Only the current attempt's events count. Every failure latches; only a new
 * `(port, token)` in the port file or a restart clears a latch.
 *
 * All state lives on [scope]'s dispatcher, which must run one coroutine at a time. Listener
 * callbacks only post to it.
 */
class ConnectMachine(
  private val seams: ConnectSeams,
  private val scope: CoroutineScope,
  private val minAppVersion: String,
) {
  private val dispatcher = checkNotNull(scope.coroutineContext[ContinuationInterceptor]) { "the scope needs a dispatcher" }

  @Volatile
  var status: Status = Status.Hidden
    private set

  /** Counts the sessions that started. The platform's Restart starts a new session for the same attempt. */
  @Volatile
  var sessions = 0
    private set

  private var attempts = 0
  private var inFlight: Job? = null
  private var restarting = false
  private var recheck = false

  /** The `(port, token)` the latest attempt used. A watcher event for it changes nothing. */
  private var lastKey: Pair<Int, String>? = null

  /** The current attempt. A retired one is gone from here, so its late events change nothing. */
  private var live: Live? = null
  private var queue: Job = Job().apply { complete() }
  private var queued: Deferred<Unit>? = null
  private var stops: Job = Job().apply { complete() }
  private var runs = 0
  private val runWaiters = mutableListOf<CompletableDeferred<Unit>>()
  @Volatile
  private var closing = false
  private val closed = CompletableDeferred<Unit>()

  private class Live(val id: Int) {
    var initialized = false

    /** `true`: `serverInitialized`; `false`: `serverStopped` came first. */
    val report = CompletableDeferred<Boolean>()
    val runs = mutableSetOf<CompletableDeferred<Unit>>()

    // A stopped client never completes its pending requests, so the run must fail here.
    fun failRuns() = runs.forEach { it.complete(Unit) }
  }

  /** A connect trigger that does not wait. It never clears a latch. */
  fun trigger() {
    scope.launch { ensure() }
  }

  /** A connect trigger that waits for the attempt in flight. It never clears a latch. */
  suspend fun ensureConnected() {
    withContext(dispatcher) { ensure() }?.join()
  }

  /**
   * Waits for a start in flight and for runs, then connects anew. Clears a latch. Requests that
   * arrive while a restart waits in the queue share it.
   */
  suspend fun restart() {
    withContext(dispatcher) { restartHere() }.join()
  }

  /** The port-file watcher's event. A delete never launches anything. */
  fun portFileChanged() {
    scope.launch {
      if (inFlight != null || restarting) recheck = true else check()
    }
  }

  fun serverInitialized(attempt: Int) {
    scope.launch {
      if (closing) return@launch
      val current = live
      if (current?.id != attempt) {
        // A client that started late must not serve while the status says why nothing serves.
        if ((status as? Status.Disconnected)?.reason?.latched == true) stopClients()
        return@launch
      }
      if (!current.initialized) {
        current.initialized = true
        current.report.complete(true)
      } else if (status == Status.Disconnected(Reason.STOPPED)) {
        // The platform's Restart starts the same descriptor again.
        connected(attempt)
      }
    }
  }

  fun serverStopped(attempt: Int, normal: Boolean) {
    scope.launch {
      if (closing) return@launch
      val current = live?.takeIf { it.id == attempt } ?: return@launch
      if (!current.initialized) {
        current.report.complete(false)
        return@launch
      }
      current.failRuns()
      if (normal) {
        // The platform's Stop or Restart: they look the same here.
        setStatus(Status.Disconnected(Reason.STOPPED))
      } else {
        latchHere(Reason.CONNECTION_ENDED)
        check()
      }
    }
  }

  /**
   * Runs [block] as a request of the current session. A latch, a stop or a retire ends it at once
   * with [SessionEnded]. With [holdsRestart] (a query), a restart waits for it.
   */
  suspend fun <T> trackRun(holdsRestart: Boolean = true, block: suspend () -> T): T {
    val ended = CompletableDeferred<Unit>()
    // Set inside the block: a cancel just after the block drops the result of `withContext`, not the cleanup.
    var registered: Live? = null
    try {
      withContext(dispatcher) {
        val current = live?.takeIf { it.initialized && (status is Status.Connected || status is Status.Unbound) } ?: throw SessionEnded()
        current.runs += ended
        if (holdsRestart) runs++
        registered = current
      }
      return coroutineScope {
        val work = async { block() }
        select {
          work.onAwait { it }
          ended.onAwait {
            work.cancel()
            throw SessionEnded()
          }
        }
      }
    } finally {
      val current = registered
      if (current != null) withContext(NonCancellable + dispatcher) {
        current.runs -= ended
        if (holdsRestart && --runs == 0) runWaiters.apply { forEach { it.complete(Unit) } }.clear()
      }
    }
  }

  /** The project or the plugin closes: no event starts anything from now on. */
  fun close() {
    closing = true
    closed.complete(Unit)
  }

  /**
   * Waits for the attempt in flight to end. After [close], it ends without a new client, and a client
   * it already started is in the platform's list, so a stop of the listed clients stops every client.
   */
  suspend fun awaitIdle() {
    withContext(dispatcher) { inFlight }?.join()
  }

  private fun ensure(): Job? {
    if (closing) return null
    val current = status
    val idle = current == Status.Hidden || (current is Status.Disconnected && !current.reason.latched)
    if (idle && inFlight == null) {
      if (!seams.trusted()) {
        setStatus(Status.Disconnected(Reason.UNTRUSTED))
        return null
      }
      inFlight = scope.launch {
        try {
          attempt()
        } finally {
          inFlight = null
          if (recheck) {
            recheck = false
            check()
          }
        }
      }
    }
    return inFlight
  }

  private fun restartHere(): Deferred<Unit> {
    queued?.let { return it }
    val previous = queue
    val next = scope.async {
      // A failed restart must not block every later one: join does not throw.
      previous.join()
      queued = null
      restarting = true
      try {
        inFlight?.join()
        if (runs > 0) {
          setStatus(Status.Starting(waitingForRun = true))
          CompletableDeferred<Unit>().also { runWaiters += it }.await()
        }
        // A request on the stopping client fails; the status must say why.
        setStatus(Status.Starting())
        retire()
        stopClients().join()
        setStatus(Status.Hidden)
        ensure()?.join()
        Unit
      } finally {
        restarting = false
      }
    }
    queued = next
    queue = next
    return next
  }

  private fun check() {
    if (status == Status.Hidden) return
    val file = seams.readPortFile() ?: return
    if (file.key == lastKey) return
    restartHere()
  }

  private suspend fun attempt() {
    val id = ++attempts
    setStatus(Status.Starting())
    val reason = try {
      connect(id)
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      // A seam that throws must still latch, or the status stays starting with nothing running.
      Reason.START_FAILED
    }
    if (reason != null) latchHere(reason)
  }

  /** `null` when connected (or closing). */
  private suspend fun connect(id: Int): Reason? {
    var file = seams.readPortFile()
    var launched = false
    var authRetried = false
    while (true) {
      // A closing project opens no socket and starts no app.
      if (closing) return null
      val current = file
      val outcome = if (current == null || seams.isStale(current)) {
        PreflightOutcome.Stale
      } else {
        lastKey = current.key
        withTimeoutOrNull(PREFLIGHT_MS) { seams.preflight(current) } ?: return Reason.TIMED_OUT
      }
      if (closing) return null
      when (outcome) {
        PreflightOutcome.Stale -> {
          // A dead pid's file never reaches the preflight; its key still must not relaunch on a watcher event.
          if (current != null) lastKey = current.key
          if (launched) return Reason.START_FAILED
          launched = true
          when (val result = seams.launch(current)) {
            is LaunchResult.Failed -> return result.reason
            is LaunchResult.Ready -> file = result.file
          }
        }
        is PreflightOutcome.Ok -> {
          if (!meetsMinimum(outcome.version, minAppVersion)) return Reason.UPDATE_APP
          return start(id, current!!)
        }
        PreflightOutcome.AuthFailed -> {
          // The app may have restarted and written a new token.
          if (authRetried) return Reason.AUTH_FAILED
          authRetried = true
          file = seams.readPortFile()
        }
        PreflightOutcome.SignedOut -> return Reason.SIGNED_OUT
        PreflightOutcome.Refused, PreflightOutcome.ConnectFailed -> return Reason.START_FAILED
        PreflightOutcome.Closed -> return Reason.CONNECTION_ENDED
      }
    }
  }

  private suspend fun start(id: Int, file: PortFile): Reason? {
    val current = Live(id)
    live = current
    // The platform drops a start while a client with the same server id is in its list.
    stopClients().join()
    if (closing || live !== current) return null
    seams.startClient(id, file)
    var waited = 0L
    while (!seams.isListed(id) && !current.report.isCompleted) {
      delay(POLL_MS)
      if (!seams.modal()) waited += POLL_MS
      if (waited >= LISTED_MS) return Reason.START_FAILED
    }
    // While closing, no event completes the report.
    val initialized = withTimeoutOrNull(REPORT_MS) {
      select<Boolean> {
        current.report.onAwait { it }
        closed.onAwait { false }
      }
    } ?: return Reason.TIMED_OUT
    if (closing || live !== current) return null
    if (!initialized) return Reason.START_FAILED
    // The platform's Stop can come between `serverInitialized` and this resume.
    if (status == Status.Disconnected(Reason.STOPPED)) return null
    connected(id)
    return null
  }

  private fun connected(attempt: Int) {
    sessions++
    setStatus(Status.Connected(attempt))
    scope.launch {
      val reason = try {
        seams.connectTimeListing(attempt)
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        null
      }
      if (live?.id != attempt) return@launch
      when (reason) {
        Reason.MISSING_REQUEST -> latchHere(reason)
        Reason.UNBOUND -> if (status == Status.Connected(attempt)) setStatus(Status.Unbound(attempt))
        else -> {}
      }
    }
  }

  /** Ends the current attempt: its late events change nothing from now on. */
  private fun retire() {
    live?.failRuns()
    live = null
  }

  private fun latchHere(reason: Reason) {
    retire()
    setStatus(Status.Disconnected(reason))
    // Else a red "Terminated" client with a Restart action stays in the platform's list.
    stopClients()
  }

  /** Never on this dispatcher's thread, and in call order, so an older stop cannot end a newer client. */
  private fun stopClients(): Job {
    val previous = stops
    return scope.launch {
      previous.join()
      try {
        seams.stopClients()
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        // A failed stop leaves nothing to undo.
      }
    }.also { stops = it }
  }

  private fun setStatus(next: Status) {
    status = next
    seams.onStatus(next)
  }

  private companion object {
    /** Longer than the server's own 30 s handshake deadline, so the server's answer arrives first. */
    const val PREFLIGHT_MS = 40_000L

    /** How long a started client may take to show in the platform's list, not counting modal time. */
    const val LISTED_MS = 10_000L

    /** How long a listed client may take to report `serverInitialized` or `serverStopped`. */
    const val REPORT_MS = 40_000L
    const val POLL_MS = 100L
  }
}
