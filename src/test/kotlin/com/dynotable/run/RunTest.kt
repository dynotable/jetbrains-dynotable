package com.dynotable.run

import com.dynotable.editor.CHANGED
import com.dynotable.editor.ServerCommands
import com.dynotable.editor.UNREADABLE
import com.dynotable.harness.harnessFixture
import com.dynotable.harness.waitFor
import com.dynotable.refusals.RUN_STATEMENT
import com.dynotable.results.BUSY
import com.dynotable.results.RESULTS_TOOL_WINDOW
import com.dynotable.results.Results
import com.dynotable.results.ResultsPanel
import com.dynotable.results.ResultsToolWindowFactory
import com.dynotable.results.View
import com.dynotable.settings.DynoTableProjectSettings
import com.dynotable.status.Action
import com.dynotable.status.Reason
import com.dynotable.status.Status
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.intellij.codeInsight.codeVision.settings.CodeVisionSettings
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.CustomizedDataContext
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.rearrangeByPromoters
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.keymap.ex.KeymapManagerEx
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import com.intellij.toolWindow.ToolWindowHeadlessManagerImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.eclipse.lsp4j.CodeLens
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import java.awt.datatransfer.DataFlavor
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.JLabel
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.plaf.basic.BasicHTML
import kotlin.time.Duration.Companion.minutes

/** Run, its results tool window and its shortcut, against the fake server. */
@TestApplication
@DisabledOnOs(OS.WINDOWS)
class RunTest {
  private val tempDir = tempPathFixture()
  private val project = projectFixture(tempDir, openAfterCreation = true)
  private val module = project.moduleFixture(tempDir, addPathToSourceRoot = true)
  private val fixture by codeInsightFixture(project, tempDir)
  private val h by harnessFixture(project)

  private val results get() = Results.getInstance(project.get())

  private fun run(test: suspend CoroutineScope.() -> Unit) = timeoutRunBlocking(2.minutes) { test() }

  private suspend fun open(name: String, text: String = TWO_STATEMENTS): Editor =
    withContext(Dispatchers.EDT) { fixture.configureByText(name, text).let { fixture.editor } }

  /** The fake answers a Run lens per line, as the server sends them. */
  private fun start() {
    h.fake.handle("textDocument/codeLens") { params, _ -> runLenses(params) }
    h.fake.start()
  }

  private suspend fun connected(): Int {
    waitFor("connected, now ${h.core.status}") { h.core.status is Status.Connected }
    return (h.core.status as Status.Connected).attempt
  }

  private suspend fun runIn(editor: Editor) = ServerCommands.getInstance(project.get()).run(withContext(Dispatchers.EDT) { RunRequest.of(editor)!! })

  private suspend fun caret(editor: Editor, line: Int, column: Int) =
    withContext(Dispatchers.EDT) { editor.caretModel.moveToOffset(editor.document.getLineStartOffset(line) + column) }

  private fun runs() = h.fake.received.filter { it.method == "workspace/executeCommand" && it.params.asJsonObject["command"].asString == RUN_STATEMENT }

  private fun lastRun(): JsonObject = runs().last().params.asJsonObject["arguments"].asJsonArray[0].asJsonObject

  /** Only the cancels of runs: the platform cancels its own requests too. */
  private fun cancels(): List<String> {
    val ids = runs().map { it.id }.toSet()
    return h.fake.received.filter { it.method == "\$/cancelRequest" && it.params.asJsonObject["id"].asString in ids }.map { it.method }
  }

  /** Starts a run that the fake holds; it returns when the run reached the fake and the view shows it running. */
  private suspend fun CoroutineScope.heldRun(editor: Editor): Job {
    h.fake.holdRuns = true
    val before = runs().size
    val job = launch { runIn(editor) }
    waitFor("the held run") { runs().size > before }
    waitFor("the running view") { results.view is View.Running }
    return job
  }

  private fun runId() = (results.view as View.Running).runId

  /** The tool window as the IDE builds it; headless, the platform registers none. */
  private suspend fun panel(): ResultsPanel = withContext(Dispatchers.EDT) {
    val manager = ToolWindowManager.getInstance(project.get()) as ToolWindowHeadlessManagerImpl
    val window = manager.getToolWindow(RESULTS_TOOL_WINDOW)
      ?: manager.doRegisterToolWindow(RESULTS_TOOL_WINDOW).also { ResultsToolWindowFactory().createToolWindowContent(project.get(), it) }
    window.contentManager.contents.single().component as ResultsPanel
  }

  private suspend fun clipboard(): String = withContext(Dispatchers.EDT) { CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor)!! }

  /** A result whose answer is [rows] under [columns], with [statement]. */
  private fun answer(columns: List<String>, rows: List<Map<String, Any?>>, statement: String = "SELECT 1") = mapOf(
    "columns" to columns, "rows" to rows, "returnedRows" to rows.size, "totalRows" to rows.size, "truncated" to false,
    "approximateBytes" to 10, "elapsedMs" to 2, "executedStatement" to statement, "effectiveRowCap" to 500,
  )

  @Test
  fun `caret Run sends the range of the statement at the caret and shows its rows`() = run {
    start()
    val editor = open("caret.dynotable.sql")
    caret(editor, 1, 5)
    runIn(editor)
    assertEquals(range(line(1, 26)), lastRun()["range"])
    assertTrue(results.view is View.Result)
  }

  @Test
  fun `selection Run sends exactly start and end`() = run {
    start()
    val editor = open("selection.partiql", "SELECT * FROM \"orders\" WHERE x = 1")
    withContext(Dispatchers.EDT) { editor.selectionModel.setSelection(0, 22) }
    runIn(editor)
    assertEquals(range(line(0, 22)), lastRun()["range"])
  }

  // The server refuses a blank-text range for opensearch.
  @Test
  fun `OpenSearch Run with no selection sends no range`() = run {
    start()
    runIn(open("q.opensearch", "// dynotable: index=orders\n{\"query\": {\"match_all\": {}}}"))
    assertFalse(lastRun().has("range"), "${lastRun()}")
  }

  // Running the whole file instead could run statements the user did not mean to run.
  @Test
  fun `a refused lens request shows the server sentence and runs nothing`() = run {
    h.fake.handle("textDocument/codeLens") { _, _ -> throw h.fake.refusal("error-signed-out.json") }
    h.fake.start()
    runIn(open("refused.dynotable.sql"))
    assertEquals(listOf(StringUtil.escapeXmlEntities(h.fake.fixture("error-signed-out.json").asJsonObject["message"].asString)), h.sentences())
    assertEquals(0, runs().size)
  }

  // The lens range is for the text before the edit; there it selects the first statement.
  @Test
  fun `an edit while the lens request is pending runs nothing`() = run {
    // Else the platform's own lens requests reach the handler too.
    val settings = CodeVisionSettings.getInstance()
    settings.codeVisionEnabled = false
    try {
      h.fake.start()
      val editor = open("edited.dynotable.sql")
      caret(editor, 1, 5)
      h.fake.handle("textDocument/codeLens") { params, _ ->
        ApplicationManager.getApplication().invokeAndWait {
          WriteCommandAction.runWriteCommandAction(project.get()) { editor.document.insertString(0, "SELECT 1;\n") }
        }
        runLenses(params)
      }
      runIn(editor)
    } finally {
      settings.codeVisionEnabled = true
    }
    assertEquals(0, runs().size)
    assertEquals(listOf(CHANGED), h.sentences())
  }

  // A JLabel renders a text that starts with <html> as HTML, which can load an image from anywhere.
  @Test
  fun `an integer cell shows 1, and a hostile column, cell and statement show as text`() = run {
    val hostile = """<html><img src="https://example.com/x.png">"""
    h.fake.handle("command:$RUN_STATEMENT") { _, _ -> answer(listOf("n", hostile), listOf(mapOf("n" to 1, hostile to hostile)), hostile) }
    start()
    val panel = panel()
    runIn(open("hostile.dynotable.sql"))
    waitFor("the result view") { panel.shown is View.Result }
    withContext(Dispatchers.EDT) {
      val table = panel.table
      assertEquals("1", (table.prepareRenderer(table.getCellRenderer(0, 0), 0, 0) as JLabel).text)
      val header = table.tableHeader.defaultRenderer.getTableCellRendererComponent(table, table.getColumnName(1), false, false, -1, 1) as JLabel
      val cell = table.prepareRenderer(table.getCellRenderer(0, 1), 0, 1) as JLabel
      for (label in listOf(header, cell)) {
        assertEquals(hostile, label.text)
        assertNull(label.getClientProperty(BasicHTML.propertyKey), "an HTML view")
      }
      // A text area never reads HTML.
      assertEquals(hostile, panel.statement.text)
    }
  }

  @Test
  fun `an empty result shows its statement and No rows, with no table`() = run {
    h.fake.handle("command:$RUN_STATEMENT") { _, _ -> answer(emptyList(), emptyList(), "SELECT * FROM \"empty\"") }
    start()
    val panel = panel()
    runIn(open("empty.dynotable.sql"))
    waitFor("the result view") { panel.shown is View.Result }
    withContext(Dispatchers.EDT) {
      assertEquals("SELECT * FROM \"empty\"", panel.statement.text)
      assertTrue(SwingUtilities.isDescendingFrom(panel.noRows, panel))
      assertFalse(SwingUtilities.isDescendingFrom(panel.table, panel))
    }
  }

  // Copy is the only way out of the view, so the only place for the formula guard.
  @Test
  fun `Copy CSV and the table's Cmd+C apply the formula guard, and Copy JSON keeps the values`() = run {
    h.fake.handle("command:$RUN_STATEMENT") { _, _ -> answer(listOf("v", "n"), listOf(mapOf("v" to "=1+1", "n" to mapOf("qty" to 1)))) }
    start()
    val panel = panel()
    runIn(open("copy.dynotable.sql"))
    waitFor("the result view") { panel.shown is View.Result }
    withContext(Dispatchers.EDT) { panel.copyCsv.doClick() }
    assertEquals("v,n\r\n'=1+1,\"{\"\"qty\"\":1}\"", clipboard())
    withContext(Dispatchers.EDT) { panel.copyJson.doClick() }
    assertEquals("[\n  {\n    \"v\": \"=1+1\",\n    \"n\": {\n      \"qty\": 1\n    }\n  }\n]", clipboard())
    withContext(Dispatchers.EDT) {
      panel.table.setRowSelectionInterval(0, 0)
      // Headless, the data manager ignores the component; this is the snapshot the IDE takes from it.
      val context = CustomizedDataContext.withSnapshot(DataContext.EMPTY_CONTEXT) { panel.table.uiDataSnapshot(it) }
      val copy = ActionManager.getInstance().getAction(IdeActions.ACTION_COPY)
      ActionUtil.performAction(copy, TestActionEvent.createTestEvent(copy, context))
    }
    assertEquals("'=1+1,\"{\"\"qty\"\":1}\"", clipboard())
  }

  @Test
  fun `Cancel sends a cancel for the run, shows the cancelled view and no notification`() = run {
    start()
    val panel = panel()
    val job = heldRun(open("cancel.partiql", "SELECT * FROM \"orders\""))
    waitFor("the running view drawn") { panel.shown is View.Running }
    withContext(Dispatchers.EDT) { panel.cancel.doClick() }
    job.join()
    waitFor("the cancel") { cancels().size == 1 }
    assertEquals(View.Cancelled, results.view)
    delay(300)
    assertEquals(emptyList<String>(), h.sentences())
  }

  // A view that was not yet redrawn can still show an earlier run's Cancel.
  @Test
  fun `a cancel for another run leaves the current run alone`() = run {
    start()
    val job = heldRun(open("stale.partiql", "SELECT * FROM \"orders\""))
    results.cancel("an-earlier-run")
    delay(500)
    assertEquals(0, cancels().size)
    assertTrue(results.view is View.Running)
    results.cancel(runId())
    job.join()
  }

  @Test
  fun `removing the tool window content cancels the run`() = run {
    start()
    val panel = panel()
    val job = heldRun(open("close.partiql", "SELECT * FROM \"orders\""))
    withContext(Dispatchers.EDT) {
      val contents = ToolWindowManager.getInstance(project.get()).getToolWindow(RESULTS_TOOL_WINDOW)!!.contentManager
      contents.removeContent(contents.contents.single { it.component === panel }, true)
    }
    job.join()
    waitFor("the cancel") { cancels().size == 1 }
  }

  @Test
  fun `a second Run while one runs is refused and sends nothing`() = run {
    start()
    val editor = open("one.partiql", "SELECT * FROM \"orders\"")
    val job = heldRun(editor)
    runIn(editor)
    assertEquals(1, runs().size)
    assertEquals(listOf(BUSY), h.sentences())
    results.cancel(runId())
    job.join()
  }

  @Test
  fun `Reconnect during a run waits for the run`() = run {
    start()
    val job = heldRun(open("reconnect.partiql", "SELECT * FROM \"orders\""))
    val attempt = connected()
    h.core.perform(Action.RECONNECT)
    h.awaitStatus(Status.Starting(waitingForRun = true))
    h.fake.releaseRuns()
    job.join()
    assertTrue(results.view is View.Result)
    assertTrue(connected() > attempt)
  }

  @Test
  fun `a sqlFiles change during a run waits for the run`() = run {
    start()
    val job = heldRun(open("settings.partiql", "SELECT * FROM \"orders\""))
    val attempt = connected()
    val settings = DynoTableProjectSettings.getInstance(project.get())
    try {
      withContext(Dispatchers.EDT) { settings.sqlFiles = listOf("**/*.x.sql") }
      h.awaitStatus(Status.Starting(waitingForRun = true))
      h.fake.releaseRuns()
      job.join()
      assertTrue(results.view is View.Result)
      assertTrue(connected() > attempt)
    } finally {
      withContext(Dispatchers.EDT) { settings.sqlFiles = emptyList() }
    }
  }

  // A stopped client never completes its requests: a run left waiting would hold the view for good.
  @Test
  fun `a latch during a run fails the run at once and frees the next Run`() = run {
    start()
    val editor = open("latch.partiql", "SELECT * FROM \"orders\"")
    val job = heldRun(editor)
    h.fake.sessions.forEach { it.socket.close() }
    h.awaitStatus(Status.Disconnected(Reason.CONNECTION_ENDED))
    withTimeout(5_000) { job.join() }
    h.fake.holdRuns = false
    h.core.perform(Action.RECONNECT)
    connected()
    runIn(editor)
    assertTrue(results.view is View.Result)
    assertFalse(BUSY in h.sentences())
  }

  @Test
  fun `a platform Restart during a run fails the run and frees the next Run`() = run {
    start()
    val editor = open("restart.partiql", "SELECT * FROM \"orders\"")
    val job = heldRun(editor)
    val attempt = connected()
    h.platformRestart(h.clients().single())
    withTimeout(5_000) { job.join() }
    h.awaitStatus(Status.Connected(attempt))
    h.fake.holdRuns = false
    runIn(editor)
    assertTrue(results.view is View.Result)
    assertEquals(emptyList<String>(), h.sentences())
  }

  @Test
  fun `Cmd+Enter during a cold start runs once the file is open on the server`() = run {
    h.fake.initializeDelayMs = 1500
    start()
    open("cold.partiql", "SELECT * FROM \"orders\"")
    withContext(Dispatchers.EDT) { fixture.performEditorAction("DynoTable.Run") }
    waitFor("the result view") { results.view is View.Result }
    assertEquals(1, runs().size)
    assertEquals(emptyList<String>(), h.sentences())
  }

  @Test
  fun `an answer that is not a RunResult shows one error and keeps the last view`() = run {
    start()
    val editor = open("unreadable.partiql", "SELECT * FROM \"orders\"")
    runIn(editor)
    val previous = results.view
    assertTrue(previous is View.Result)
    h.fake.handle("command:$RUN_STATEMENT") { _, _ -> JsonObject() }
    runIn(editor)
    assertEquals(listOf(UNREADABLE), h.sentences())
    assertEquals(previous, results.view)
  }

  @Test
  fun `Run after connection-ended shows the reason and never the running view`() = run {
    start()
    val editor = open("ended.partiql", "SELECT * FROM \"orders\"")
    connected()
    h.fake.sessions.forEach { it.socket.close() }
    h.awaitStatus(Status.Disconnected(Reason.CONNECTION_ENDED))
    waitFor("the latch's notification") { h.notifications.isNotEmpty() }
    val views = CopyOnWriteArrayList<View>()
    val disposable = Disposer.newDisposable()
    try {
      results.addListener(disposable) { views += it }
      runIn(editor)
    } finally {
      Disposer.dispose(disposable)
    }
    assertEquals(listOf(Reason.CONNECTION_ENDED.sentence, Reason.CONNECTION_ENDED.sentence), h.sentences())
    assertEquals(emptyList<View>(), views)
    assertEquals(0, runs().size)
  }

  // As the IDE picks the action for a keystroke: the keymap's actions, then the promoters, then the first enabled one.
  @Test
  fun `Cmd+Enter in a DynoTable file runs, and in a plain file splits the line`() = run {
    for ((n, keymap, keystroke) in listOf(Triple(1, "\$default", "control ENTER"), Triple(2, "Mac OS X 10.5+", "meta ENTER"))) {
      // A second file of the same name would be renamed, and so lose its file type.
      val ids = KeymapManagerEx.getInstanceEx().getKeymap(keymap)!!.getActionIdList(KeyboardShortcut(KeyStroke.getKeyStroke(keystroke), null))
      assertEquals("DynoTable.Run", firstEnabled(open("$n.dynotable.sql"), ids), keymap)
      assertEquals("EditorSplitLine", firstEnabled(open("$n.txt"), ids), keymap)
    }
  }

  private suspend fun firstEnabled(editor: Editor, ids: List<String>): String? = withContext(Dispatchers.EDT) {
    val context = (editor as EditorEx).dataContext
    val actions: List<AnAction> = ids.mapNotNull { ActionManager.getInstance().getAction(it) }
    rearrangeByPromoters(actions, context).firstOrNull { action ->
      val event = TestActionEvent.createTestEvent(action, context)
      ActionUtil.updateAction(action, event)
      event.presentation.isEnabled
    }?.let { ActionManager.getInstance().getId(it) }
  }

  private companion object {
    const val TWO_STATEMENTS = "SELECT * FROM \"orders\";\nSELECT * FROM \"customers\";"

    fun line(n: Int, length: Int) = Range(Position(n, 0), Position(n, length))

    fun range(range: Range): JsonElement = Gson().toJsonTree(range)

    fun runLenses(params: JsonElement): List<CodeLens> {
      val uri = params.asJsonObject["textDocument"].asJsonObject["uri"].asString
      return TWO_STATEMENTS.split('\n').mapIndexed { n, text ->
        CodeLens(line(n, 0), Command("Run", RUN_STATEMENT, listOf(mapOf("uri" to uri, "range" to line(n, text.length)))), null)
      }
    }
  }
}
