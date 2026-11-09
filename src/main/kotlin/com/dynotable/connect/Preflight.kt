package com.dynotable.connect

import com.dynotable.protocol.AUTH_FAILED_CODE
import com.dynotable.protocol.PortFile
import com.dynotable.protocol.failureCode
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageServer
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The server sends nothing the preflight needs. */
private object Silent : LanguageClient {
  override fun telemetryEvent(`object`: Any?) {}

  override fun publishDiagnostics(diagnostics: PublishDiagnosticsParams?) {}

  override fun showMessage(messageParams: MessageParams?) {}

  override fun showMessageRequest(requestParams: ShowMessageRequestParams?): CompletableFuture<MessageActionItem> =
    CompletableFuture.completedFuture(null)

  override fun logMessage(message: MessageParams?) {}
}

/**
 * `initialize` with the token over the plugin's own socket, then `shutdown` and `exit`. The platform
 * only logs why its own `initialize` failed, so this tells the reason first. It sends no profile: a
 * roster of exactly one profile still binds it, for this short connection only. It connects to the
 * address the platform's socket channel uses.
 */
suspend fun runPreflight(file: PortFile): PreflightOutcome = withContext(Dispatchers.IO) {
  val socket = Socket()
  val executor = Executors.newCachedThreadPool { Thread(it, "DynoTable preflight").apply { isDaemon = true } }
  try {
    try {
      socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), file.port), CONNECT_TIMEOUT_MS)
    } catch (_: ConnectException) {
      return@withContext PreflightOutcome.Stale
    } catch (_: IOException) {
      return@withContext PreflightOutcome.ConnectFailed
    }
    val launcher = Launcher.Builder<LanguageServer>()
      .setLocalService(Silent)
      .setRemoteInterface(LanguageServer::class.java)
      .setInput(socket.getInputStream())
      .setOutput(socket.getOutputStream())
      .setExecutorService(executor)
      .create()
    val listening = launcher.startListening()
    val server = launcher.remoteProxy
    val params = InitializeParams().apply {
      processId = ProcessHandle.current().pid().toInt()
      capabilities = ClientCapabilities()
      initializationOptions = JsonObject().apply { addProperty("token", file.token) }
    }
    val initialize = server.initialize(params)
    // A pending request does not fail when the socket closes; the end of the stream tells it.
    while (!initialize.isDone && !listening.isDone) delay(POLL_MS)
    if (!initialize.isDone) return@withContext PreflightOutcome.Closed
    val result = try {
      initialize.get()
    } catch (e: ExecutionException) {
      val error = (e.cause as? ResponseErrorException)?.responseError ?: return@withContext PreflightOutcome.Closed
      return@withContext when {
        error.code == AUTH_FAILED_CODE -> PreflightOutcome.AuthFailed
        failureCode(error) == "signed-out" -> PreflightOutcome.SignedOut
        else -> PreflightOutcome.Refused
      }
    }
    try {
      server.shutdown().get(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
      server.exit()
    } catch (_: Exception) {
      // The answer is in; a failed goodbye changes nothing.
    }
    PreflightOutcome.Ok(result.serverInfo?.version)
  } finally {
    socket.close()
    executor.shutdownNow()
  }
}

private const val CONNECT_TIMEOUT_MS = 5_000
private const val SHUTDOWN_TIMEOUT_MS = 5_000L
private const val POLL_MS = 20L
