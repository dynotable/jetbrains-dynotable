package com.dynotable.results

import com.dynotable.protocol.ProtocolException
import com.dynotable.protocol.RunResult
import com.dynotable.protocol.jsNumber
import com.intellij.ide.CopyProvider
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.plaf.basic.BasicHTML
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableCellRenderer

const val RESULTS_TOOL_WINDOW = "DynoTable Results"
const val BUSY = "A DynoTable run is in progress. Cancel it in the DynoTable Results tool window, or wait for it to end."

sealed interface RunOutcome {
  data object Ok : RunOutcome

  /** Another run holds the results: nothing was sent. */
  data object Busy : RunOutcome

  /** The answer is not a `RunResult`. */
  data object Unreadable : RunOutcome

  data class Failed(val error: Throwable, val cancelled: Boolean) : RunOutcome
}

/** The project's one results view. It holds one run at a time. */
@Service(Service.Level.PROJECT)
class Results(private val project: Project) {
  @Volatile
  var view: View = View.Empty
    private set

  private val current = AtomicReference<Run?>()
  private val listeners = CopyOnWriteArrayList<(View) -> Unit>()

  private class Run(val id: String) {
    @Volatile
    var cancelled = false

    @Volatile
    var work: Job? = null
  }

  fun addListener(parent: Disposable, listener: (View) -> Unit) {
    listeners += listener
    Disposer.register(parent) { listeners -= listener }
  }

  /** Runs [send] with the running view shown. Sends nothing while another run holds the view. */
  suspend fun execute(send: suspend () -> Any?): RunOutcome {
    val run = Run(UUID.randomUUID().toString())
    if (!current.compareAndSet(null, run)) return RunOutcome.Busy
    try {
      val before = view
      open()
      show(View.Running(run.id))
      val answer = try {
        coroutineScope {
          val work = async { send() }
          run.work = work
          if (run.cancelled) work.cancel()
          work.await()
        }
      } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
        if (!run.cancelled) throw e
        show(View.Cancelled)
        return RunOutcome.Failed(e, cancelled = true)
      } catch (e: Exception) {
        show(if (run.cancelled) View.Cancelled else before)
        return RunOutcome.Failed(e, run.cancelled)
      }
      // A reply already in flight can still arrive after the user's Cancel.
      if (run.cancelled) {
        show(View.Cancelled)
        return RunOutcome.Failed(CancellationException("cancelled"), cancelled = true)
      }
      val result = try {
        RunResult.from(answer)
      } catch (_: ProtocolException) {
        show(before)
        return RunOutcome.Unreadable
      }
      show(View.Result(result))
      return RunOutcome.Ok
    } finally {
      current.set(null)
    }
  }

  /** Acts only on the run [runId] names: a view that was not yet redrawn can show an older run. */
  fun cancel(runId: String) {
    val run = current.get()?.takeIf { it.id == runId } ?: return
    run.cancelled = true
    run.work?.cancel()
  }

  fun cancelRun() {
    current.get()?.let { cancel(it.id) }
  }

  fun copy(result: RunResult, asJson: Boolean) {
    CopyPasteManager.getInstance().setContents(StringSelection(if (asJson) toJson(result) else toCsv(result)))
    StatusBar.Info.set("Copied ${jsNumber(result.returnedRows)} rows as ${if (asJson) "JSON" else "CSV"}.", project)
  }

  private fun show(next: View) {
    view = next
    listeners.forEach { it(next) }
  }

  private fun open() = ApplicationManager.getApplication().invokeLater({
    val window = ToolWindowManager.getInstance(project).getToolWindow(RESULTS_TOOL_WINDOW) ?: return@invokeLater
    window.isAvailable = true
    if (window.contentManager.contentCount == 0) addResultsContent(project, window)
    window.show()
  }, project.disposed)

  companion object {
    fun getInstance(project: Project): Results = project.service()
  }
}

class ResultsToolWindowFactory : ToolWindowFactory, DumbAware {
  // The first run makes it available.
  override fun shouldBeAvailable(project: Project) = false

  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) = addResultsContent(project, toolWindow)
}

private fun addResultsContent(project: Project, window: ToolWindow) {
  val panel = ResultsPanel(project)
  window.contentManager.addContent(ContentFactory.getInstance().createContent(panel, null, false).apply {
    isCloseable = true
    setDisposer(panel)
  })
}

/** Shows the [Results] view. Every server string shows as plain text, never as Swing HTML. */
class ResultsPanel(project: Project) : JPanel(BorderLayout()), Disposable {
  private val results = Results.getInstance(project)
  internal val summary = plainLabel()
  internal val copyCsv = JButton("Copy CSV")
  internal val copyJson = JButton("Copy JSON")
  internal val cancel = JButton("Cancel")
  internal val statement = JBTextArea().apply {
    isEditable = false
    lineWrap = true
  }
  internal val table = ResultsTable()
  internal val noRows = JBLabel("No rows.")
  private val body = JPanel(BorderLayout())

  /** The view drawn last. */
  @Volatile
  internal var shown: View = View.Empty
    private set

  init {
    add(JPanel(FlowLayout(FlowLayout.LEFT)).apply { listOf(summary, copyCsv, copyJson, cancel).forEach(::add) }, BorderLayout.NORTH)
    add(body, BorderLayout.CENTER)
    cancel.addActionListener { (shown as? View.Running)?.let { results.cancel(it.runId) } }
    copyCsv.addActionListener { (shown as? View.Result)?.let { results.copy(it.run, asJson = false) } }
    copyJson.addActionListener { (shown as? View.Result)?.let { results.copy(it.run, asJson = true) } }
    results.addListener(this) { view -> ApplicationManager.getApplication().invokeLater { render(view) } }
    render(results.view)
  }

  private fun render(view: View) {
    shown = view
    body.removeAll()
    copyCsv.isVisible = view is View.Result
    copyJson.isVisible = view is View.Result
    cancel.isVisible = view is View.Running
    summary.text = when (view) {
      View.Empty -> "Run a statement to see its rows here."
      is View.Running -> "Running…"
      View.Cancelled -> "The run was cancelled."
      is View.Result -> view.shown.summary
    }
    if (view is View.Result) {
      statement.text = view.shown.executedStatement
      body.add(statement, BorderLayout.NORTH)
      table.show(view.shown)
      body.add(if (view.shown.columns.isEmpty()) noRows else JBScrollPane(table), BorderLayout.CENTER)
    }
    body.revalidate()
    body.repaint()
  }

  // Removing the content ends the run, as closing the panel does in VS Code.
  override fun dispose() = results.cancelRun()
}

/** Cmd/Ctrl+C copies the selected rows as the guarded CSV. */
class ResultsTable : JBTable(), UiDataProvider {
  private var rows: ResultView = ResultView("", emptyList(), emptyList(), "")

  private val copier = object : CopyProvider {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun isCopyEnabled(dataContext: DataContext) = selectedRowCount > 0

    override fun isCopyVisible(dataContext: DataContext) = true

    override fun performCopy(dataContext: DataContext) {
      val selected = selectedRows.map { rows.cells[convertRowIndexToModel(it)] }
      CopyPasteManager.getInstance().setContents(StringSelection(csv(selected)))
    }
  }

  init {
    setDefaultRenderer(Any::class.java, plain(getDefaultRenderer(Any::class.java)))
    tableHeader.defaultRenderer = plain(tableHeader.defaultRenderer)
  }

  fun show(view: ResultView) {
    rows = view
    model = object : AbstractTableModel() {
      override fun getRowCount() = view.cells.size

      override fun getColumnCount() = view.columns.size

      override fun getColumnName(column: Int) = view.columns[column]

      override fun getValueAt(row: Int, column: Int) = view.cells[row][column]
    }
  }

  override fun uiDataSnapshot(sink: DataSink) {
    sink[PlatformDataKeys.COPY_PROVIDER] = copier
  }
}

private const val HTML_DISABLE = "html.disable"

private fun plainLabel() = JBLabel().apply { putClientProperty(HTML_DISABLE, true) }

private fun plain(renderer: TableCellRenderer) = TableCellRenderer { table, value, selected, focused, row, column ->
  renderer.getTableCellRendererComponent(table, value, selected, focused, row, column).also(::plain)
}

private fun plain(component: Component) {
  if (component is JLabel && component.getClientProperty(HTML_DISABLE) != true) {
    component.putClientProperty(HTML_DISABLE, true)
    // Drops the HTML view the first text made, before anything lays it out.
    BasicHTML.updateRenderer(component, component.text)
  }
}
