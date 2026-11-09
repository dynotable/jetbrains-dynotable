package com.dynotable.connect

import com.dynotable.editor.PlanCodeVisionProvider
import com.dynotable.explorer.Explorer
import com.dynotable.explorer.Listing
import com.dynotable.explorer.listingOf
import com.dynotable.files.DynoTableFileType
import com.dynotable.profiles.ProfilePicker
import com.dynotable.protocol.ListTablesResult
import com.dynotable.protocol.METHOD_NOT_FOUND_CODE
import com.dynotable.protocol.PortFile
import com.dynotable.protocol.failureCode
import com.dynotable.refusals.NO_SENTENCE
import com.dynotable.refusals.notify
import com.dynotable.settings.DynoTableAppSettings
import com.dynotable.settings.DynoTableConfigurable
import com.dynotable.status.Action
import com.dynotable.status.Reason
import com.dynotable.status.Status
import com.dynotable.ui.StatusWidget
import com.google.gson.JsonObject
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.ide.trustedProjects.TrustedProjectsListener
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectCloseListener
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.WindowManager
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspClientManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException

const val DOWNLOAD_URL = "https://dynotable.com/download"
const val DOCS_URL = "https://dynotable.com/docs/editors"

/**
 * The project's connection to DynoTable: the [ConnectMachine] with the real app, socket and platform
 * clients, the port-file watcher, the status and the reason actions.
 */
@Service(Service.Level.PROJECT)
class ConnectCore(private val project: Project, private val cs: CoroutineScope) : Disposable {
  private val testMode = ApplicationManager.getApplication().isUnitTestMode
  private val overrides = Overrides.of(testMode, System::getProperty)
  private val platform = Platform.current()
  private val home = System.getProperty("user.home")
  private val env: Map<String, String> = System.getenv()
  private val settings get() = DynoTableAppSettings.getInstance()

  /** Opens a page in the browser. Tests replace it: in test mode the default throws. */
  internal var openExternal: (String) -> Unit = openExternal(testMode)

  @Volatile
  private var descriptor: DynoTableClientDescriptor? = null

  /** Starts the app. In test mode the default starts only a fake binary; only a test may replace it. */
  @Volatile
  internal var spawner: Spawner = Spawner.of(testMode, overrides.testAppRoot)
    set(value) {
      check(testMode) { "The spawner is a test seam" }
      field = value
    }

  private val launcher = Launcher(
    platform,
    home,
    env,
    overrides.minAppVersion,
    launcherOff = { settings.userDataDir.isNotEmpty() },
    locate = { locateApp(Fs.Local, platform, env, home, settings.appPath, overrides.testAppRoot) },
    readPortFile = ::readPort,
    spawner = { this@ConnectCore.spawner.start(it) },
  )

  private val seams = object : ConnectSeams {
    override fun trusted() = TrustedProjects.isProjectTrusted(project)

    override fun readPortFile() = readPort()

    override fun isStale(file: PortFile) = isStale(file, platform)

    override suspend fun launch(stale: PortFile?) = launcher.launch(stale)

    override suspend fun preflight(file: PortFile) = runPreflight(file)

    override suspend fun stopClients() = withContext(Dispatchers.IO) { manager().stopClients(DynoTableIntegrationProvider::class.java) }

    override fun startClient(attempt: Int, file: PortFile) {
      val next = DynoTableClientDescriptor(project, attempt, file, AttemptListener(machine, attempt))
      descriptor = next
      manager().ensureClientStarted(DynoTableIntegrationProvider::class.java, next)
    }

    override fun isListed(attempt: Int) = clientOf(attempt) != null

    override suspend fun modal() =
      withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) { ModalityState.current() != ModalityState.nonModal() }

    // Its answer also fills the explorer: a bound listing can take seconds.
    override suspend fun connectTimeListing(attempt: Int): Reason? {
      val client = clientOf(attempt) ?: return null
      val tables = CompletableDeferred<Listing<ListTablesResult>>()
      Explorer.getInstance(project).connected(attempt, client, tables)
      return try {
        // A stop never completes a pending request: the session's end must end the wait.
        val answer = machine.trackRun(holdsRestart = false) { client.sendRequest { (it as DynoTableServer).listTables(JsonObject()) } }
        tables.complete(listingOf(answer, ListTablesResult::from))
        null
      } catch (_: SessionEnded) {
        null
      } catch (e: ResponseErrorException) {
        tables.complete(Listing.Failed(e.responseError.message.orEmpty().ifBlank { NO_SENTENCE }))
        when {
          e.responseError.code == METHOD_NOT_FOUND_CODE -> Reason.MISSING_REQUEST
          failureCode(e.responseError) == "profile-required" -> Reason.UNBOUND
          // `signed-out` too: the app ends the session itself.
          else -> null
        }
      } finally {
        tables.complete(Listing.Failed(Reason.CONNECTION_ENDED.sentence))
      }
    }

    override fun onStatus(status: Status) {
      project.getServiceIfCreated(Explorer::class.java)?.changed()
      ApplicationManager.getApplication().invokeLater({
        WindowManager.getInstance().getStatusBar(project)?.updateWidget(StatusWidget.ID)
        PlanCodeVisionProvider.refresh(project)
      }, project.disposed)
      if (status is Status.Disconnected && status.reason.latched) notify(status.reason)
    }
  }

  val machine: ConnectMachine = ConnectMachine(seams, CoroutineScope(cs.coroutineContext + SupervisorJob(cs.coroutineContext.job) + Dispatchers.Default.limitedParallelism(1)), overrides.minAppVersion)

  val status: Status get() = machine.status

  init {
    watchPortFile()
    ApplicationManager.getApplication().messageBus.connect(cs).subscribe(
      TrustedProjectsListener.TOPIC,
      object : TrustedProjectsListener {
        override fun onProjectTrusted(project: Project) {
          if (project == this@ConnectCore.project) trigger()
        }
      },
    )
  }

  fun trigger() = machine.trigger()

  /**
   * A command's connect: waits for the attempt in flight. `null` when nothing serves; then the user
   * has seen why (a reason latched by this call was shown when it latched).
   */
  suspend fun client(): LspClient? {
    val before = machine.status
    machine.ensureConnected()
    session()?.let { return clientOf(it) }
    when (val status = machine.status) {
      is Status.Disconnected -> if (!status.reason.latched || (before as? Status.Disconnected)?.reason?.latched == true) notify(status.reason)
      else -> notify(project, "DynoTable is reconnecting. Try again shortly.", NotificationType.INFORMATION)
    }
    return null
  }

  /** The current session's client, with no connect; `null` while nothing serves. */
  fun serving(): LspClient? = session()?.let(::clientOf)

  /** The attempt whose session serves, bound or not; `null` while nothing serves. */
  fun session(): Int? = when (val status = machine.status) {
    is Status.Connected -> status.attempt
    is Status.Unbound -> status.attempt
    else -> null
  }

  /**
   * The server publishes diagnostics for every document it opened, and refuses a command on a
   * document it has not opened. `false` when the file cannot be open on the server; else waits up to
   * 3 s for that sign, then lets the command go.
   */
  suspend fun awaitServerOpened(file: VirtualFile): Boolean {
    val current = descriptor ?: return false
    val local = TrustedProjects.isProjectTrusted(project) && readAction {
      file.isValid && file.fileType is DynoTableFileType &&
        (FileEditorManager.getInstance(project).isFileOpen(file) || FileDocumentManager.getInstance().isFileModified(file))
    }
    if (!local) return false
    val uri = current.getFileUri(file)
    withTimeoutOrNull(OPENED_WAIT_MS) {
      while (uri !in current.published) delay(OPENED_POLL_MS)
    }
    return true
  }

  /** A `sqlFiles` change: another set of files needs a new client. A latch holds until Reconnect. */
  fun sqlFilesChanged() {
    when (val current = machine.status) {
      // A `.sql` file open in an editor may be DynoTable SQL now.
      Status.Hidden -> if (FileEditorManager.getInstance(project).openFiles.any { it.fileType is DynoTableFileType }) trigger()
      is Status.Disconnected -> if (!current.reason.latched) cs.launch { machine.restart() }
      else -> cs.launch { machine.restart() }
    }
  }

  /** Another user data directory is another app: its port file decides, as after a restart. */
  fun userDataDirChanged() {
    if (machine.status != Status.Hidden) cs.launch { machine.restart() }
  }

  fun perform(action: Action) {
    when (action) {
      Action.RECONNECT -> cs.launch { machine.restart() }
      Action.OPEN_APP -> cs.launch(Dispatchers.IO) { launcher.openApp()?.let { notify(project, it.sentence, NotificationType.ERROR) } }
      Action.DOWNLOAD -> openExternal(DOWNLOAD_URL)
      Action.OPEN_DOCS -> openExternal(DOCS_URL)
      Action.SET_APP_PATH -> ApplicationManager.getApplication().invokeLater({
        ShowSettingsUtil.getInstance().showSettingsDialog(project, DynoTableConfigurable::class.java)
      }, project.disposed)
      Action.SELECT_PROFILE -> ProfilePicker.getInstance(project).launch()
      Action.TRUST_PROJECT -> ApplicationManager.getApplication().invokeLater({
        // The platform's own dialog, with its warning. Its yes trusts the project and tells the listeners.
        val trust = ActionManager.getInstance().getAction(TRUST_ACTION) ?: return@invokeLater
        ActionUtil.performAction(trust, AnActionEvent.createEvent(SimpleDataContext.getProjectContext(project), null, ActionPlaces.NOTIFICATION, ActionUiKind.NONE, null))
      }, project.disposed)
    }
  }

  /** Shows [reason]'s sentence with its actions. */
  fun notify(reason: Reason) = notify(project, reason.sentence, NotificationType.ERROR, reason.actions.map { it.label to { perform(it) } })

  private fun manager() = LspClientManager.getInstance(project)

  private fun clientOf(attempt: Int): LspClient? =
    manager().getClients(DynoTableIntegrationProvider::class.java).firstOrNull { (it.descriptor as? DynoTableClientDescriptor)?.attempt == attempt }

  private fun readPort(): PortFile? = readPortFile(Fs.Local, portFilePath(platform, env, home, settings.userDataDir, overrides))

  /**
   * Polls the port file with `java.nio`: the app replaces it with an atomic rename, and its folder
   * does not exist until the app first runs. A delete is no event.
   */
  private fun watchPortFile() {
    cs.launch(Dispatchers.IO) {
      var seen = readPort()?.key
      while (true) {
        delay(WATCH_MS)
        val key = readPort()?.key
        if (key != null && key != seen) machine.portFileChanged()
        seen = key
      }
    }
  }

  override fun dispose() = machine.close()

  companion object {
    private const val OPENED_WAIT_MS = 3_000L
    private const val OPENED_POLL_MS = 50L
    private const val WATCH_MS = 500L
    private const val TRUST_ACTION = "ShowTrustProjectDialog"

    fun getInstance(project: Project): ConnectCore = project.service()
  }
}

/** Project close and IDE exit stop the clients: from now on, no stop starts anything. */
class ConnectClosing : ProjectCloseListener {
  override fun projectClosing(project: Project) {
    project.getServiceIfCreated(ConnectCore::class.java)?.machine?.close()
  }
}

/** The platform calls no integration provider in an untrusted project, but the status must still say why nothing serves. */
class EditorTrigger(private val project: Project) : FileEditorManagerListener {
  override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
    if (file.fileType is DynoTableFileType) ConnectCore.getInstance(project).trigger()
  }
}

/** The platform tells nothing about editors that were open before the project finished opening. */
class RestoredEditors : ProjectActivity {
  override suspend fun execute(project: Project) {
    val files = readAction { FileEditorManager.getInstance(project).openFiles.toList() }
    if (files.any { it.fileType is DynoTableFileType }) ConnectCore.getInstance(project).trigger()
  }
}
