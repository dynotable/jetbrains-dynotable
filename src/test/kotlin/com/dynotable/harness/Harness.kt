package com.dynotable.harness

import com.dynotable.connect.ConnectCore
import com.dynotable.connect.TEST_APP_ROOT_PROPERTY
import com.dynotable.connect.TEST_MIN_APP_VERSION_PROPERTY
import com.dynotable.connect.TEST_USER_DATA_DIR_PROPERTY
import com.dynotable.fake.FakeApp
import com.dynotable.fake.FakeRun
import com.dynotable.fake.FakeServer
import com.dynotable.status.Status
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.ListPopup
import com.intellij.openapi.ui.popup.ListPopupStep
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfo
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.platform.lsp.api.LspIntegrationProvider
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import com.intellij.testFramework.junit5.fixture.testFixture
import com.intellij.testFramework.runInEdtAndGet
import com.intellij.ui.UiInterceptors
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBList
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** A row of a list popup, as the user sees it. */
data class PopupRow(val text: String, val selectable: Boolean)

/**
 * One integration test's world: a test user-data dir that the [fake] serves, a test app root, every
 * notification, and scripted popup answers. The plugin reads the dirs through the test seams of
 * `Overrides`, so nothing points at the real app or its data dir.
 */
class Harness internal constructor(val project: Project, dir: Path, private val disposable: Disposable) {
  /** Does not exist until the fake starts or a fake app serves: the plugin must handle both. */
  val userDataDir: Path = dir.resolve("userData")
  val portFile: Path = userDataDir.resolve("lsp-port.json")
  val appRoot: Path = dir.resolve("apps").createDirectories()

  /** Not started: [FakeServer.start] writes the port file. */
  val fake = FakeServer(portFile)

  private val apps = mutableListOf<FakeApp>()

  /** Every notification posted while the test runs, the platform's own too. */
  val notifications = CopyOnWriteArrayList<Notification>()

  /** The rows of every list popup shown, in order. */
  val popups = CopyOnWriteArrayList<List<PopupRow>>()

  /** The row text that the next list popups pick; `null` closes them with no choice. */
  @Volatile
  var popupAnswer: String? = null

  init {
    val record = object : Notifications {
      override fun notify(notification: Notification) {
        notifications += notification
      }
    }
    // A notification for a project goes to the project bus only, one with no project to the application bus.
    ApplicationManager.getApplication().messageBus.connect(disposable).subscribe(Notifications.TOPIC, record)
    project.messageBus.connect(disposable).subscribe(Notifications.TOPIC, record)
    UiInterceptors.registerPersistent(disposable, object : UiInterceptors.PersistentUiInterceptor<ListPopup>(ListPopup::class.java) {
      override fun shouldIntercept(component: ListPopup) = true

      override fun doIntercept(component: ListPopup, owner: RelativePoint?) = answer(component)
    })
  }

  private fun answer(popup: ListPopup) {
    @Suppress("UNCHECKED_CAST")
    val step = popup.listStep as ListPopupStep<Any?>
    popups += step.values.map { PopupRow(step.getTextFor(it), step.isSelectable(it)) }
    val index = step.values.indexOfFirst { step.getTextFor(it) == popupAnswer && step.isSelectable(it) }
    if (index < 0) {
      popup.cancel()
      return
    }
    // As the platform's ChooserInterceptor: select in the list of the popup that is not shown, then choose.
    UIUtil.findComponentOfType(popup.content, JBList::class.java)!!.selectedIndex = index
    popup.handleSelect(true)
  }

  /** Clicks the action labelled [action] of [notification], as the user does in its balloon. */
  fun click(notification: Notification, action: String) {
    val match = notification.actions.singleOrNull { it.templateText == action }
      ?: error("no action \"$action\" in ${notification.actions.map { it.templateText }}")
    runInEdtAndGet { Notification.fire(notification, match, null) }
  }

  /**
   * Installs a fake app where the app locator looks for it on this OS, at [version] (macOS reads it
   * from `Info.plist`). Start `n` does `runs[n - 1]`.
   */
  fun installApp(vararg runs: FakeRun, version: String = fake.version): FakeApp {
    check(!SystemInfo.isWindows) { "the fake app is a POSIX shell script" }
    val app = if (SystemInfo.isMac) {
      val contents = appRoot.resolve("DynoTable.app/Contents").createDirectories()
      contents.resolve("Info.plist").writeText("<key>CFBundleShortVersionString</key><string>$version</string>")
      FakeApp(contents.resolve("MacOS"), runs.toList(), name = "DynoTable")
    } else {
      FakeApp(appRoot, runs.toList())
    }
    return app.also { apps += it }
  }

  /** A fake app start that writes the port file of the started [fake] and stays alive, as a serving app does. */
  fun serving(): FakeRun = FakeRun.Serve(portFile, fake.port, fake.token)

  val core: ConnectCore get() = ConnectCore.getInstance(project)

  suspend fun awaitStatus(expected: Status, ms: Long = 15_000) {
    waitFor("status $expected, now ${core.status}", ms) { core.status == expected }
  }

  /** The sentences of the notifications posted so far, the platform's too. */
  fun sentences(): List<String> = notifications.map { it.content }

  /** The platform's Restart in the Services view and the Language Services widget. Internal API: tests only. */
  fun platformRestart(client: LspClient) = callManager("restartClient", client)

  /** The platform's Stop in the Services view. Internal API: tests only. */
  fun platformStop(client: LspClient) = callManager("stopRunningServer", client)

  private fun callManager(name: String, client: LspClient) {
    val manager = LspClientManager.getInstance(project)
    // An `internal` Kotlin function has a JVM name with the module suffix.
    manager.javaClass.methods.single { it.name == "$name\$intellij_platform_lsp_impl" }.invoke(manager, client)
  }

  fun clients(): List<LspClient> =
    LspIntegrationProvider.EP_NAME.extensionList.flatMap { LspClientManager.getInstance(project).getClients(it.javaClass) }

  /**
   * Stops every LSP client and waits until its listener thread ends: a client left running fails
   * the test teardown with "Thread leaked: LSP Listener". Not on the EDT: a graceful stop can block.
   */
  suspend fun stopClients() {
    withContext(Dispatchers.Default) {
      LspIntegrationProvider.EP_NAME.extensionList.forEach { LspClientManager.getInstance(project).stopClients(it.javaClass) }
    }
    try {
      waitFor("the LSP listener threads to end") { Thread.getAllStackTraces().keys.none { it.name.startsWith("LSP Listener") } }
    } catch (e: AssertionError) {
      throw AssertionError("${e.message} (fake sessions ${fake.sessions.map { it.initialized }}, received ${fake.received.map { it.method }}, clients ${clients().map { it.state }}):\n${listenerStacks()}")
    }
  }

  private fun listenerStacks() = Thread.getAllStackTraces().filterKeys { it.name.startsWith("LSP Listener") }.values
    .joinToString("\n\n") { stack -> stack.take(25).joinToString("\n") }

  internal suspend fun close() {
    try {
      // As the project's close does: else a stop by the teardown reads as the platform's Stop, and a trigger connects again.
      project.getServiceIfCreated(ConnectCore::class.java)?.machine?.close()
      stopClients()
    } finally {
      apps.forEach { it.close() }
      fake.close()
    }
  }
}

/** Polls [condition] every 50 ms; fails after [ms]. */
suspend fun waitFor(what: String, ms: Long = 15_000, condition: suspend () -> Boolean) {
  withTimeoutOrNull(ms) {
    while (!condition()) delay(50)
  } ?: throw AssertionError("timed out after $ms ms waiting for $what")
}

/**
 * Highlights the fixture's file until [condition] holds. Not `awaitDiagnosticsFromLspServer`: it
 * waits only for diagnostics that come after the call, so it misses those of a fast server.
 */
suspend fun awaitHighlighting(fixture: CodeInsightTestFixture, what: String, ms: Long = 15_000, condition: (List<HighlightInfo>) -> Boolean) {
  waitFor(what, ms) { condition(runInEdtAndGet { fixture.doHighlighting() }) }
}

/** Runs [block] on the EDT while a modal dialog is open, for example to close that dialog. */
suspend fun <T> inAnyModality(block: suspend () -> T): T = withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) { block() }

/**
 * The [Harness] for one test. It sets the test seams before the test and clears them after it;
 * [project] must be the project the test opens files in. Its dirs are outside the project.
 */
fun harnessFixture(project: TestFixture<Project>): TestFixture<Harness> = testFixture("harness") {
  val disposable = Disposer.newDisposable("harness")
  val harness = Harness(project.init(), tempPathFixture().init(), disposable)
  val seams = mapOf(
    TEST_USER_DATA_DIR_PROPERTY to harness.userDataDir.toString(),
    TEST_APP_ROOT_PROPERTY to harness.appRoot.toString(),
    // The fixtures were recorded from an app that can predate the minimum version.
    TEST_MIN_APP_VERSION_PROPERTY to harness.fake.version,
  )
  seams.forEach { (key, value) -> System.setProperty(key, value) }
  initialized(harness) {
    try {
      harness.close()
    } finally {
      seams.keys.forEach(System::clearProperty)
      Disposer.dispose(disposable)
    }
  }
}
