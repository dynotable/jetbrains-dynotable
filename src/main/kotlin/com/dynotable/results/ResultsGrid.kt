package com.dynotable.results

import com.intellij.ide.CopyProvider
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.EditorTextField
import com.intellij.ui.PopupHandler
import com.intellij.ui.SearchTextField
import com.intellij.ui.TableSpeedSearch
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Point
import java.awt.datatransfer.StringSelection
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.KeyStroke
import javax.swing.RowFilter
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableModel
import javax.swing.table.TableRowSorter

private const val MIN_WIDTH = 40
private const val MAX_WIDTH = 400
private const val FIT_SAMPLE = 200

/** The read-only grid of one result: typed cells, sort, filter, hidden columns and copies. */
class ResultsTable : JBTable(), UiDataProvider {
  internal var view: ResultView = ResultView("", emptyList(), emptyList(), emptyList(), emptyList())
    private set
  internal var filter = ""
    set(value) {
      field = value
      refilter()
    }
  private var sorter: TableRowSorter<TableModel>? = null

  private val copier = object : CopyProvider {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun isCopyEnabled(dataContext: DataContext) = selectedRowCount > 0

    override fun isCopyVisible(dataContext: DataContext) = true

    override fun performCopy(dataContext: DataContext) {
      CopyPasteManager.getInstance().setContents(StringSelection(selectionCsv(view, selection(), header = false)))
    }
  }

  init {
    isStriped = true
    autoResizeMode = AUTO_RESIZE_OFF
    fillsViewportHeight = true
    // Data in the editor font, as the IDE's own data grids show it.
    font = EditorUtil.getEditorFont()
    rowHeight = getFontMetrics(font).height + JBUI.scale(6)
    setShowGrid(false)
    showVerticalLines = true
    gridColor = JBColor.border()
    tableHeader.reorderingAllowed = true
    setDefaultRenderer(Any::class.java, plain(CellRenderer()))
    tableHeader.defaultRenderer = plain(tableHeader.defaultRenderer)
    TableSpeedSearch.installOn(this)
  }

  fun show(next: ResultView) {
    view = next
    model = object : AbstractTableModel() {
      override fun getRowCount() = next.cells.size

      override fun getColumnCount() = next.columns.size

      override fun getColumnName(column: Int) = next.columns[column]

      override fun getValueAt(row: Int, column: Int) = next.cells[row][column]
    }
    sorter = TableRowSorter<TableModel>(model).apply {
      next.columns.indices.forEach { c -> setComparator(c, comparator(c)) }
      rowFilter = object : RowFilter<TableModel, Int>() {
        override fun include(entry: Entry<out TableModel, out Int>) =
          matchesFilter(visibleColumns().map { next.cells[entry.identifier][it] }, filter)
      }
    }
    rowSorter = sorter
    fitColumns()
  }

  /** Numbers sort by value when every cell of the column that has a value is a number. */
  private fun comparator(column: Int): Comparator<String> {
    val kinds = view.kinds.map { it[column] }.filter { it != CellKind.MISSING }
    if (kinds.isNotEmpty() && kinds.all { it == CellKind.NUMBER }) return Comparator(::compareNumberTexts)
    return compareBy<String> { it.isEmpty() }.thenComparator { a, b -> a.compareTo(b, ignoreCase = true) }
  }

  fun fitColumns() {
    for (c in 0 until columnCount) {
      val header = tableHeader.defaultRenderer.getTableCellRendererComponent(this, getColumnName(c), false, false, -1, c).preferredSize.width
      val cells = (0 until minOf(rowCount, FIT_SAMPLE)).maxOfOrNull { r -> prepareRenderer(getCellRenderer(r, c), r, c).preferredSize.width } ?: 0
      columnModel.getColumn(c).preferredWidth = (maxOf(header, cells) + JBUI.scale(12)).coerceIn(JBUI.scale(MIN_WIDTH), JBUI.scale(MAX_WIDTH))
    }
  }

  /** Model indices of the shown columns, in view order. */
  fun visibleColumns(): List<Int> = (0 until columnCount).map(::convertColumnIndexToModel)

  /** The selected rows (in view order) and the shown columns, as model indices. */
  fun selection() = Selection(selectedRows.map(::convertRowIndexToModel), visibleColumns())

  /** Filters again: the filter reads only the shown columns. */
  fun refilter() {
    sorter?.sort()
  }

  /** The cell the caret is on, as model indices. */
  fun currentCell(): Pair<Int, Int>? {
    val row = selectionModel.leadSelectionIndex.takeIf { it in 0 until rowCount } ?: return null
    val column = columnModel.selectionModel.leadSelectionIndex.takeIf { it in 0 until columnCount } ?: 0
    if (columnCount == 0) return null
    return convertRowIndexToModel(row) to convertColumnIndexToModel(column)
  }

  override fun uiDataSnapshot(sink: DataSink) {
    sink[PlatformDataKeys.COPY_PROVIDER] = copier
  }

  private inner class CellRenderer : DefaultTableCellRenderer() {
    override fun getTableCellRendererComponent(table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int): Component {
      super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
      val kind = view.kinds[convertRowIndexToModel(row)][convertColumnIndexToModel(column)]
      horizontalAlignment = if (kind == CellKind.NUMBER) SwingConstants.RIGHT else SwingConstants.LEFT
      if (!isSelected && (kind == CellKind.NULL || kind == CellKind.STRUCTURE)) foreground = NamedColorUtil.getInactiveTextColor()
      // JSON starts with `{` or `[`, never `<html>`: the tooltip stays text.
      toolTipText = if (kind == CellKind.STRUCTURE) text else null
      border = JBUI.Borders.empty(0, 6)
      return this
    }
  }
}

/** The grid with row numbers, its menu, the value viewer and the record view; [toolbar] holds the filter. */
class ResultsGrid(private val project: Project, parent: Disposable) : JPanel(BorderLayout()) {
  val table = ResultsTable()
  internal val filterField = SearchTextField(false)
  internal val shownNote = plainLabel().apply { foreground = NamedColorUtil.getInactiveTextColor() }
  val toolbar = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply {
    isOpaque = false
    add(shownNote)
    add(filterField)
  }
  private val rowNumbers = RowNumbers()

  // A second table, so each number has the exact height and baseline of its row.
  private val numbers = JBTable(rowNumbers).apply {
    font = table.font
    rowHeight = table.rowHeight
    isFocusable = false
    rowSelectionAllowed = false
    setShowGrid(false)
    tableHeader = null
    background = JBUI.CurrentTheme.ToolWindow.background()
    setDefaultRenderer(Any::class.java, DefaultTableCellRenderer().apply {
      horizontalAlignment = SwingConstants.RIGHT
      foreground = NamedColorUtil.getInactiveTextColor()
      border = JBUI.Borders.empty(0, 8, 0, 6)
    })
  }

  init {
    val scroll = ScrollPaneFactory.createScrollPane(table, true).apply {
      setRowHeaderView(numbers)
      rowHeader.background = numbers.background
    }
    add(scroll, BorderLayout.CENTER)
    filterField.textEditor.emptyText.text = "Filter rows"
    filterField.textEditor.columns = 16
    filterField.addDocumentListener(object : DocumentAdapter() {
      override fun textChanged(e: DocumentEvent) {
        table.filter = filterField.text
        refresh()
      }
    })
    PopupHandler.installPopupMenu(table, menu(), "DynoTableResults")
    table.addMouseListener(object : MouseAdapter() {
      override fun mousePressed(e: MouseEvent) = pointAt(e)

      override fun mouseReleased(e: MouseEvent) = pointAt(e)

      // A right-click on an unselected row selects it, so the menu acts on the row under the pointer.
      private fun pointAt(e: MouseEvent) {
        if (!SwingUtilities.isRightMouseButton(e)) return
        val row = table.rowAtPoint(e.point).takeIf { it >= 0 } ?: return
        val column = table.columnAtPoint(e.point).takeIf { it >= 0 } ?: return
        if (!table.isRowSelected(row)) table.changeSelection(row, column, false, false)
        else table.columnModel.selectionModel.setSelectionInterval(column, column)
      }
    })
    object : DoubleClickListener() {
      override fun onDoubleClick(event: MouseEvent): Boolean {
        if (table.rowAtPoint(event.point) < 0) return false
        showValue()
        return true
      }
    }.installOn(table)
    DumbAwareAction.create { showValue() }
      .registerCustomShortcutSet(CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK)), table, parent)
  }

  fun show(view: ResultView) {
    filterField.text = ""
    table.show(view)
    table.rowSorter.addRowSorterListener { refresh() }
    numbers.rowHeight = table.rowHeight
    refresh()
  }

  private fun refresh() {
    rowNumbers.update(table.rowCount)
    val digits = table.rowCount.coerceAtLeast(1).toString().length
    numbers.preferredScrollableViewportSize = Dimension(numbers.getFontMetrics(numbers.font).charWidth('0') * digits + JBUI.scale(16), 0)
    numbers.revalidate()
    val total = table.view.cells.size
    shownNote.text = if (table.rowCount == total) "" else "${table.rowCount} of $total rows shown"
  }

  private fun menu() = DefaultActionGroup().apply {
    val hasRow = { table.currentCell() != null }
    val hasSelection = { table.selectedRowCount > 0 }
    add(action("Copy Value", hasRow) { table.currentCell()?.let { (r, c) -> copy(table.view.cells[r][c], "the value") } })
    add(action("Copy Selection as CSV", hasSelection) { copyRows("CSV") { selectionCsv(table.view, it, header = true) } })
    add(action("Copy Selection as JSON", hasSelection) { copyRows("JSON") { selectionJson(table.view, it) } })
    add(action("Copy Selection as Markdown", hasSelection) { copyRows("Markdown") { selectionMarkdown(table.view, it) } })
    add(action("Copy Column", hasRow) {
      table.currentCell()?.let { (_, c) ->
        // One selected row is where the pointer is; more rows narrow the column to them.
        val rows = if (table.selectedRowCount > 1) table.selection().rows else (0 until table.rowCount).map(table::convertRowIndexToModel)
        copy(csv(rows.map { listOf(table.view.cells[it][c]) }), "the column")
      }
    })
    addSeparator()
    add(action("View Value", hasRow) { showValue() })
    add(action("Record View", hasRow) { showRecord() })
    addSeparator()
    add(action("Hide Column", { hasRow() && table.columnCount > 1 }) {
      val column = table.columnModel.selectionModel.leadSelectionIndex.takeIf { it in 0 until table.columnCount } ?: return@action
      table.columnModel.removeColumn(table.columnModel.getColumn(column))
      table.refilter()
      refresh()
    })
    add(action("Show All Columns", { table.columnCount < table.view.columns.size }) {
      table.createDefaultColumnsFromModel()
      table.fitColumns()
      table.refilter()
      refresh()
    })
  }

  private fun copyRows(format: String, text: (Selection) -> String) {
    val selection = table.selection()
    copy(text(selection), "${selection.rows.size} ${if (selection.rows.size == 1) "row" else "rows"} as $format")
  }

  private fun copy(text: String, what: String) {
    CopyPasteManager.getInstance().setContents(StringSelection(text))
    StatusBar.Info.set("Copied $what.", project)
  }

  private fun showValue() {
    val (row, column) = table.currentCell() ?: return
    val text = viewerText(table.view, row, column)
    val structured = table.view.kinds[row][column] == CellKind.STRUCTURE
    val type = if (structured) FileTypeManager.getInstance().getFileTypeByExtension("json") else FileTypeManager.getInstance().getFileTypeByExtension("txt")
    val editor = EditorTextField(text, project, type).apply {
      isViewer = true
      setOneLineMode(false)
      preferredSize = Dimension(JBUI.scale(560), JBUI.scale(320))
    }
    val copyButton = JButton("Copy").apply { addActionListener { copy(text, "the value") } }
    val content = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
      border = JBUI.Borders.empty(8)
      add(plainLabel().apply { this.text = table.view.columns[column] }, BorderLayout.NORTH)
      add(editor, BorderLayout.CENTER)
      add(JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply { add(copyButton) }, BorderLayout.SOUTH)
    }
    popup(content, editor)
  }

  private fun showRecord() {
    val (row, _) = table.currentCell() ?: return
    val record = JBTable(object : AbstractTableModel() {
      override fun getRowCount() = table.view.columns.size

      override fun getColumnCount() = 2

      override fun getColumnName(column: Int) = if (column == 0) "Column" else "Value"

      override fun getValueAt(r: Int, c: Int) = if (c == 0) table.view.columns[r] else table.view.cells[row][r]
    }).apply {
      setDefaultRenderer(Any::class.java, plain(DefaultTableCellRenderer()))
      tableHeader.defaultRenderer = plain(tableHeader.defaultRenderer)
      isStriped = true
    }
    val scroll = ScrollPaneFactory.createScrollPane(record).apply { preferredSize = Dimension(JBUI.scale(560), JBUI.scale(360)) }
    popup(scroll, record)
  }

  private fun popup(content: JComponent, focus: JComponent) {
    val cell = table.selectionModel.leadSelectionIndex.coerceAtLeast(0)
    val rect = table.getCellRect(cell, table.columnModel.selectionModel.leadSelectionIndex.coerceAtLeast(0), true)
    JBPopupFactory.getInstance().createComponentPopupBuilder(content, focus)
      .setResizable(true)
      .setMovable(true)
      .setRequestFocus(true)
      .createPopup()
      .show(RelativePoint(table, Point(rect.x, rect.y + rect.height)))
  }

  private fun action(text: String, enabled: () -> Boolean, run: () -> Unit) = object : DumbAwareAction(text) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
      e.presentation.isEnabled = enabled()
    }

    override fun actionPerformed(e: AnActionEvent) = run()
  }

  private class RowNumbers : AbstractTableModel() {
    private var size = 0

    fun update(next: Int) {
      size = next
      fireTableDataChanged()
    }

    override fun getRowCount() = size

    override fun getColumnCount() = 1

    override fun getValueAt(row: Int, column: Int) = (row + 1).toString()
  }
}
