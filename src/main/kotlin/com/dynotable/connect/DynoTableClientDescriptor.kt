package com.dynotable.connect

import com.dynotable.files.DynoTableFileType
import com.dynotable.files.OpenSearchFileType
import com.dynotable.files.PartiQLFileType
import com.dynotable.editor.DynoTableCustomization
import com.dynotable.protocol.PortFile
import com.dynotable.settings.DynoTableWorkspaceSettings
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.Lsp4jClient
import com.intellij.platform.lsp.api.LspCommunicationChannel
import com.intellij.platform.lsp.api.LspIntegrationProvider
import com.intellij.platform.lsp.api.LspServerListener
import com.intellij.platform.lsp.api.LspServerNotificationsHandler
import com.intellij.platform.lsp.api.ProjectWideLspClientDescriptor
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.eclipse.lsp4j.services.LanguageServer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/** The server's custom requests (PROTOCOL.md). */
interface DynoTableServer : LanguageServer {
  @JsonRequest("dynotable/listProfiles")
  fun listProfiles(): CompletableFuture<JsonElement>

  @JsonRequest("dynotable/listTables")
  fun listTables(params: JsonObject): CompletableFuture<JsonElement>
}

/** Starts nothing itself: the plugin starts its clients through [ConnectCore], after its preflight. */
class DynoTableIntegrationProvider : LspIntegrationProvider {
  // The platform calls this under a read lock: it must only schedule.
  override fun fileOpened(project: Project, file: VirtualFile, clientStarter: LspIntegrationProvider.LspClientStarter) {
    if (file.fileType is DynoTableFileType) ConnectCore.getInstance(project).trigger()
  }
}

/**
 * One attempt's client. It holds the `(port, token)` that passed the preflight and never reads the
 * port file itself, so the platform cannot connect with a key the preflight did not check.
 */
class DynoTableClientDescriptor(
  project: Project,
  val attempt: Int,
  private val file: PortFile,
  private val listener: LspServerListener,
) : ProjectWideLspClientDescriptor(project, "DynoTable") {
  /**
   * The URIs the server published diagnostics for in the current session: it does so for every
   * document it opened. The platform's Restart starts a new session on this descriptor.
   */
  @Volatile
  var published: Set<String> = emptySet()
    private set

  override fun isSupportedFile(file: VirtualFile) = file.fileType is DynoTableFileType

  override val lspCommunicationChannel = LspCommunicationChannel.Socket(file.port, startProcess = false)

  // The profile is read at each start: a session binds its profile at `initialize` only.
  override fun createInitializationOptions(): Any = JsonObject().apply {
    addProperty("token", file.token)
    DynoTableWorkspaceSettings.getInstance(project).profile?.let { addProperty("profile", it.id) }
  }

  override fun getLanguageId(file: VirtualFile) = when (file.fileType) {
    PartiQLFileType -> "partiql"
    OpenSearchFileType -> "opensearch"
    else -> "sql"
  }

  override val lsp4jServerClass = DynoTableServer::class.java

  override val lspServerListener = listener

  override fun createLsp4jClient(handler: LspServerNotificationsHandler) =
    Lsp4jClient(ServerHandler(handler, ConcurrentHashMap.newKeySet<String>().also { published = it }))

  override val lspCustomization = DynoTableCustomization()
}

/** Forwards the platform's events for one attempt. They arrive on the "LSP Listener" thread and must not block. */
class AttemptListener(private val machine: ConnectMachine, private val attempt: Int) : LspServerListener {
  override fun serverInitialized(params: InitializeResult) = machine.serverInitialized(attempt)

  override fun serverStopped(shutdownNormally: Boolean) = machine.serverStopped(attempt, shutdownNormally)
}
