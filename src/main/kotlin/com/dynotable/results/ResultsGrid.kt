package com.dynotable.results

import com.intellij.icons.AllIcons
import com.intellij.ide.CopyProvider
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.wm.StatusBar
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.table.JBTable
import javax.swing.Box
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.JBCheckBox
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.KeyStroke
import javax.swing.RowFilter
import javax.swing.ScrollPaneConstants
import javax.swing.SortOrder
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellRenderer
import javax.swing.table.TableModel
import javax.swing.table.TableRowSorter

private const val MIN_WIDTH = 48
private const val MAX_WIDTH = 400
private const val FIT_SAMPLE = 200
private const val SORT_ICON_AREA = 22
private const val NULL_TEXT = "<null>"

/** The data grid's own colours: the editor background, as the IDE's data grids use. */
private fun gridBackground(): Color = EditorColorsManager.getInstance().globalScheme.defaultBackground

private fun searchResultBackground(): Color =
  EditorColorsManager.getInstance().globalScheme.getAttributes(EditorColors.SEARCH_RESULT_ATTRIBUTES)?.backgroundColor ?: UIUtil.getTableSelectionBackground(false)

/** Sorts on a header click only when the click is on the column's sort icon. */
private class GridSorter(model: TableModel) : TableRowSorter<TableModel>(model) {
  var headerClickSorts = true

  override fun toggleSortOrder(column: Int) {
    if (headerClickSorts) super.toggleSortOrder(column)
  }
}

/** The read-only grid of one result: typed cells, cell selection, sort, filter, hidden columns and copies. */
class ResultsTable : JBTable(), UiDataProvider {
  internal var view: ResultView = ResultView("", emptyList(), emptyList(), emptyList(), emptyList())
    private set
  internal var filter = ""
    set(value) {
      field = value
      refilter()
    }
  private var sorter: GridSorter? = null

  /** The find bar's text: matching cells show in the search result colour. */
  internal var highlight = ""
    set(value) {
      field = value
      repaint()
    }

  private val copier = object : CopyProvider {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun isCopyEnabled(dataContext: DataContext) = selectedRowCount > 0

    override fun isCopyVisible(dataContext: DataContext) = true

    // One cell copies its value; more cells copy as guarded CSV, as a spreadsheet paste reads them.
    override fun performCopy(dataContext: DataContext) {
      val selection = selection()
      val text = if (selection.rows.size == 1 && selection.columns.size == 1) view.cells[selection.rows[0]][selection.columns[0]] else selectionCsv(view, selection, header = false)
      CopyPasteManager.getInstance().setContents(StringSelection(text))
    }
  }

  init {
    // The setter, not JTable's protected field of the same name, which Kotlin would bind here.
    setCellSelectionEnabled(true)
    autoResizeMode = AUTO_RESIZE_OFF
    fillsViewportHeight = true
    font = EditorUtil.getEditorFont()
    rowHeight = getFontMetrics(font).height + JBUI.scale(10)
    setShowGrid(true)
    gridColor = JBColor.border()
    background = gridBackground()
    tableHeader.reorderingAllowed = true
    tableHeader.defaultRenderer = HeaderRenderer()
    setDefaultRenderer(Any::class.java, plain(CellRenderer()))
  }

  fun show(next: ResultView) {
    view = next
    model = object : AbstractTableModel() {
      override fun getRowCount() = next.cells.size

      override fun getColumnCount() = next.columns.size

      override fun getColumnName(column: Int) = next.columns[column]

      override fun getValueAt(row: Int, column: Int) = next.cells[row][column]
    }
    sorter = GridSorter(model).apply {
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
      columnModel.getColumn(c).preferredWidth = (maxOf(header, cells) + JBUI.scale(8)).coerceIn(JBUI.scale(MIN_WIDTH), JBUI.scale(MAX_WIDTH))
    }
  }

  /** Model indices of the shown columns, in view order. */
  fun visibleColumns(): List<Int> = (0 until columnCount).map(::convertColumnIndexToModel)

  /** The selected cells as rows × columns (model indices, view order). With no column selected, every shown column. */
  fun selection(): Selection {
    val columns = if (selectedColumnCount > 0) selectedColumns.toList() else (0 until columnCount).toList()
    return Selection(selectedRows.map(::convertRowIndexToModel), columns.map(::convertColumnIndexToModel))
  }

  /** The next (or previous) cell after the caret whose text holds [text], in reading order; it wraps. */
  fun findNext(text: String, forward: Boolean): Boolean {
    if (text.isBlank() || rowCount == 0 || columnCount == 0) return false
    val count = rowCount * columnCount
    val start = (selectionModel.leadSelectionIndex.coerceAtLeast(0)) * columnCount + columnModel.selectionModel.leadSelectionIndex.coerceAtLeast(0)
    for (step in 1..count) {
      val i = Math.floorMod(start + if (forward) step else -step, count)
      val (r, c) = i / columnCount to i % columnCount
      if ((getValueAt(r, c)?.toString() ?: "").contains(text.trim(), ignoreCase = true)) {
        changeSelection(r, c, false, false)
        return true
      }
    }
    return false
  }

  /** Filters again: the filter reads only the shown columns. */
  fun refilter() {
    sorter?.sort()
  }

  fun sortBy(column: Int, order: SortOrder) {
    val modelColumn = convertColumnIndexToModel(column)
    sorter?.sortKeys = if (order == SortOrder.UNSORTED) emptyList() else listOf(javax.swing.RowSorter.SortKey(modelColumn, order))
  }

  /** A press on the header sorts only on the column's sort icon; elsewhere the click selects the column. */
  internal fun headerPressedAt(x: Int, column: Int) {
    val rect = tableHeader.getHeaderRect(column)
    sorter?.headerClickSorts = x >= rect.x + rect.width - JBUI.scale(SORT_ICON_AREA)
  }

  internal fun headerReleased() {
    SwingUtilities.invokeLater { sorter?.headerClickSorts = true }
  }

  fun selectColumn(column: Int) {
    if (rowCount == 0) return
    setColumnSelectionInterval(column, column)
    setRowSelectionInterval(0, rowCount - 1)
  }

  fun selectRows(from: Int, to: Int) {
    if (columnCount == 0) return
    setRowSelectionInterval(from, to)
    setColumnSelectionInterval(0, columnCount - 1)
  }

  /** The cell the caret is on, as model indices. */
  fun currentCell(): Pair<Int, Int>? {
    val row = selectionModel.leadSelectionIndex.takeIf { it in 0 until rowCount } ?: return null
    if (columnCount == 0) return null
    val column = columnModel.selectionModel.leadSelectionIndex.takeIf { it in 0 until columnCount } ?: 0
    return convertRowIndexToModel(row) to convertColumnIndexToModel(column)
  }

  private fun sortOrderOf(column: Int): SortOrder =
    sorter?.sortKeys?.firstOrNull { it.column == convertColumnIndexToModel(column) }?.sortOrder ?: SortOrder.UNSORTED

  override fun uiDataSnapshot(sink: DataSink) {
    sink[PlatformDataKeys.COPY_PROVIDER] = copier
  }

  private inner class CellRenderer : DefaultTableCellRenderer() {
    override fun getTableCellRendererComponent(table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int): Component {
      super.getTableCellRendererComponent(table, value, isSelected, false, row, column)
      val kind = view.kinds[convertRowIndexToModel(row)][convertColumnIndexToModel(column)]
      font = table.font
      if (kind == CellKind.NULL) text = NULL_TEXT
      horizontalAlignment = if (kind == CellKind.NUMBER) SwingConstants.RIGHT else SwingConstants.LEFT
      if (!isSelected) {
        val match = highlight.isNotBlank() && (value?.toString() ?: "").contains(highlight.trim(), ignoreCase = true)
        background = if (match) searchResultBackground() else table.background
        foreground = if (kind == CellKind.NULL) NamedColorUtil.getInactiveTextColor() else table.foreground
      }
      // JSON starts with `{` or `[`, never `<html>`: the tooltip stays text.
      toolTipText = if (kind == CellKind.STRUCTURE) text else null
      border = JBUI.Borders.empty(0, 5)
      return this
    }
  }

  /** The column name on the panel colour, with the sort icon at the right edge, between column lines. */
  private inner class HeaderRenderer : TableCellRenderer {
    private val title = plainLabel()
    private val sort = JLabel()
    private val cell = JPanel(BorderLayout()).apply {
      add(title, BorderLayout.CENTER)
      add(sort, BorderLayout.EAST)
    }

    override fun getTableCellRendererComponent(table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int): Component {
      title.text = value?.toString().orEmpty()
      title.font = table.font
      title.foreground = UIUtil.getLabelForeground()
      sort.icon = when (if (column >= 0) sortOrderOf(column) else SortOrder.UNSORTED) {
        SortOrder.ASCENDING -> AllIcons.General.ArrowUp
        SortOrder.DESCENDING -> AllIcons.General.ArrowDown
        else -> AllIcons.ObjectBrowser.Sorted
      }
      sort.border = JBUI.Borders.empty(0, 4)
      cell.background = UIUtil.getPanelBackground()
      cell.border = BorderFactory.createCompoundBorder(JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 1), JBUI.Borders.empty(4, 8, 4, 2))
      return cell
    }
  }
}

/** The grid with row numbers and the Record and Value side panel; [toolbar] holds the filter. */
class ResultsGrid(private val project: Project, parent: Disposable) : JPanel(BorderLayout()) {
  val table = ResultsTable()
  internal val shownNote = plainLabel().apply { foreground = NamedColorUtil.getInactiveTextColor() }
  val toolbar = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply {
    isOpaque = false
    add(shownNote)
  }
  internal val find = FindBar(table) { refresh() }
  private val rowNumbers = RowNumbers()

  // A second table, so each number has the exact height and baseline of its row.
  private val numbers = JBTable(rowNumbers).apply {
    font = table.font
    rowHeight = table.rowHeight
    isFocusable = false
    setShowGrid(false)
    tableHeader = null
    background = gridBackground()
    setDefaultRenderer(Any::class.java, object : DefaultTableCellRenderer() {
      override fun getTableCellRendererComponent(t: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int): Component {
        super.getTableCellRendererComponent(t, value, false, false, row, column)
        font = table.font
        horizontalAlignment = SwingConstants.LEFT
        foreground = if (row < table.rowCount && table.isRowSelected(row)) table.foreground else NamedColorUtil.getInactiveTextColor()
        background = gridBackground()
        border = JBUI.Borders.empty(0, 6)
        return this
      }
    })
  }
  private val details = DetailsPanel(project, table)
  private val splitter = JBSplitter(false, 0.7f).apply {
    firstComponent = ScrollPaneFactory.createScrollPane(table, true).apply {
      setRowHeaderView(numbers)
      rowHeader.background = gridBackground()
      viewport.background = gridBackground()
      setCorner(ScrollPaneConstants.UPPER_LEFT_CORNER, JPanel().apply {
        background = UIUtil.getPanelBackground()
        border = JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 1)
      })
      numbers.border = JBUI.Borders.customLine(JBColor.border(), 0, 0, 0, 1)
    }
  }

  val showFind: AnAction = object : DumbAwareAction("Find", "Find text in the cells", AllIcons.Actions.Search) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
      e.presentation.isEnabled = table.columnCount > 0
    }

    override fun actionPerformed(e: AnActionEvent) = find.open()
  }

  val showRecord: AnAction = object : DumbAwareAction("Show Record View", "Show the row at the caret as a list of columns and values", AllIcons.Actions.PreviewDetails) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
      e.presentation.isEnabled = table.currentCell() != null
    }

    override fun actionPerformed(e: AnActionEvent) = openDetails(DetailsPanel.Tab.RECORD)
  }

  val openValue: AnAction = object : DumbAwareAction("Open in Value Editor", "Show the full value of the cell at the caret", AllIcons.Actions.Edit) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
      e.presentation.isEnabled = table.currentCell() != null
    }

    override fun actionPerformed(e: AnActionEvent) = openDetails(DetailsPanel.Tab.VALUE)
  }

  init {
    add(find, BorderLayout.NORTH)
    add(splitter, BorderLayout.CENTER)
    val menuKey = if (SystemInfo.isMac) InputEvent.META_DOWN_MASK else InputEvent.CTRL_DOWN_MASK
    // On the table too: a component's own shortcut wins over the IDE's Find there.
    showFind.registerCustomShortcutSet(CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_F, menuKey)), table, parent)
    showRecord.registerCustomShortcutSet(CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK or menuKey)), table, parent)
    openValue.registerCustomShortcutSet(CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK)), table, parent)
    PopupHandler.installPopupMenu(table, cellMenu(), "DynoTableResults")
    PopupHandler.installPopupMenu(table.tableHeader, headerMenu(), "DynoTableResultsHeader")
    table.addMouseListener(object : MouseAdapter() {
      override fun mousePressed(e: MouseEvent) = pointAt(e)

      // A right-click outside the selection selects the cell under the pointer, so the menu acts on it.
      private fun pointAt(e: MouseEvent) {
        if (!SwingUtilities.isRightMouseButton(e)) return
        val row = table.rowAtPoint(e.point).takeIf { it >= 0 } ?: return
        val column = table.columnAtPoint(e.point).takeIf { it >= 0 } ?: return
        if (!table.isCellSelected(row, column)) table.changeSelection(row, column, false, false)
      }
    })
    table.tableHeader.addMouseListener(object : MouseAdapter() {
      private var column = -1

      override fun mousePressed(e: MouseEvent) {
        column = table.columnAtPoint(e.point)
        if (column >= 0 && SwingUtilities.isLeftMouseButton(e)) table.headerPressedAt(e.x, column)
        if (SwingUtilities.isRightMouseButton(e) && column >= 0) table.selectColumn(column)
      }

      override fun mouseClicked(e: MouseEvent) {
        val rect = if (column >= 0) table.tableHeader.getHeaderRect(column) else return
        if (SwingUtilities.isLeftMouseButton(e) && e.x < rect.x + rect.width - JBUI.scale(SORT_ICON_AREA)) table.selectColumn(column)
      }

      override fun mouseReleased(e: MouseEvent) = table.headerReleased()
    })
    numbers.addMouseListener(object : MouseAdapter() {
      private var anchor = -1

      override fun mousePressed(e: MouseEvent) {
        val row = numbers.rowAtPoint(e.point).takeIf { it in 0 until table.rowCount } ?: return
        if (e.isShiftDown && anchor >= 0) table.selectRows(minOf(anchor, row), maxOf(anchor, row)) else table.selectRows(row, row).also { anchor = row }
        table.requestFocusInWindow()
      }
    })
    table.selectionModel.addListSelectionListener {
      numbers.repaint()
      details.update()
    }
    table.columnModel.selectionModel.addListSelectionListener { details.update() }
    object : DoubleClickListener() {
      override fun onDoubleClick(event: MouseEvent): Boolean {
        if (table.rowAtPoint(event.point) < 0) return false
        openDetails(DetailsPanel.Tab.VALUE)
        return true
      }
    }.installOn(table)
    details.onClose = { splitter.secondComponent = null }
  }

  fun show(view: ResultView) {
    find.reset()
    table.show(view)
    table.rowSorter.addRowSorterListener {
      refresh()
      table.tableHeader.repaint()
    }
    numbers.rowHeight = table.rowHeight
    refresh()
    details.update()
  }

  private fun openDetails(tab: DetailsPanel.Tab) {
    details.select(tab)
    if (splitter.secondComponent == null) splitter.secondComponent = details
  }

  private fun refresh() {
    rowNumbers.update(table.rowCount)
    val digits = table.rowCount.coerceAtLeast(1).toString().length
    numbers.preferredScrollableViewportSize = java.awt.Dimension(numbers.getFontMetrics(numbers.font).charWidth('0') * digits + JBUI.scale(14), 0)
    numbers.revalidate()
    val total = table.view.cells.size
    shownNote.text = if (table.rowCount == total) "" else "${table.rowCount} of $total rows shown"
  }

  private fun cellMenu() = DefaultActionGroup().apply {
    add(showRecord)
    add(openValue)
    addSeparator()
    add(ActionManager.getInstance().getAction(IdeActions.ACTION_COPY))
    add(DefaultActionGroup("Copy As", true).apply {
      add(action("CSV", { table.selectedRowCount > 0 }) { copyRows("CSV") { selectionCsv(table.view, it, header = true) } })
      add(action("JSON", { table.selectedRowCount > 0 }) { copyRows("JSON") { selectionJson(table.view, it) } })
      add(action("Markdown", { table.selectedRowCount > 0 }) { copyRows("Markdown") { selectionMarkdown(table.view, it) } })
    })
    addSeparator()
    add(showFind)
  }

  private fun headerMenu() = DefaultActionGroup().apply {
    add(action("Copy Column Name", { leadColumn() >= 0 }) {
      CopyPasteManager.getInstance().setContents(StringSelection(table.getColumnName(leadColumn())))
    })
    add(action("Select Column", { leadColumn() >= 0 }) { table.selectColumn(leadColumn()) })
    addSeparator()
    addAll(columnActions())
    add(action("Reset Columns Width", { table.columnCount > 0 }) { table.fitColumns() })
    add(Separator.create("ORDER BY"))
    add(action("Ascending", { leadColumn() >= 0 }) { table.sortBy(leadColumn(), SortOrder.ASCENDING) })
    add(action("Descending", { leadColumn() >= 0 }) { table.sortBy(leadColumn(), SortOrder.DESCENDING) })
    addSeparator()
    add(action("Clear Sorting", { table.rowSorter?.sortKeys?.isNotEmpty() == true }) { table.sortBy(0, SortOrder.UNSORTED) })
  }

  private fun leadColumn() = table.columnModel.selectionModel.leadSelectionIndex.takeIf { it in 0 until table.columnCount } ?: -1

  private fun columnActions() = listOf(
    action("Hide Column", { leadColumn() >= 0 && table.columnCount > 1 }) {
      table.columnModel.removeColumn(table.columnModel.getColumn(leadColumn()))
      table.refilter()
      refresh()
    },
    action("Hide Other Columns", { leadColumn() >= 0 && table.columnCount > 1 }) {
      val keep = table.columnModel.getColumn(leadColumn())
      table.columnModel.columns.toList().filter { it !== keep }.forEach(table.columnModel::removeColumn)
      table.refilter()
      refresh()
    },
    action("Show All Columns", { table.columnCount < table.view.columns.size }) {
      table.createDefaultColumnsFromModel()
      table.fitColumns()
      table.refilter()
      refresh()
    },
  )

  private fun copyRows(format: String, text: (Selection) -> String) {
    val selection = table.selection()
    CopyPasteManager.getInstance().setContents(StringSelection(text(selection)))
    StatusBar.Info.set("Copied ${selection.rows.size} ${if (selection.rows.size == 1) "row" else "rows"} as $format.", project)
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

/** The side panel: the row at the caret as columns and values (Record), or the full value of its cell (Value). */
private class DetailsPanel(project: Project, private val table: ResultsTable) : JPanel(BorderLayout()) {
  enum class Tab { RECORD, VALUE }

  var onClose: () -> Unit = {}
  private var tab = Tab.VALUE
  private val cards = CardLayout()
  private val body = JPanel(cards)
  // One read-only field per column, under its name, as the IDE's record view lays a row out.
  private val record = JPanel(VerticalLayout(JBUI.scale(4))).apply { border = JBUI.Borders.empty(8, 10) }
  private val json = FileTypeManager.getInstance().getFileTypeByExtension("json")
  private val text = FileTypeManager.getInstance().getFileTypeByExtension("txt")
  private val value = EditorTextField("", project, text).apply {
    isViewer = true
    setOneLineMode(false)
    border = JBUI.Borders.empty()
  }

  init {
    val tabs = DefaultActionGroup(tabAction("Record", Tab.RECORD), tabAction("Value", Tab.VALUE))
    val left = ActionManager.getInstance().createActionToolbar("DynoTableResultsDetails", tabs, true).apply { targetComponent = this@DetailsPanel }
    val close = DefaultActionGroup(object : DumbAwareAction("Close", null, AllIcons.Actions.Close) {
      override fun actionPerformed(e: AnActionEvent) = onClose()
    })
    val right = ActionManager.getInstance().createActionToolbar("DynoTableResultsDetailsClose", close, true).apply { targetComponent = this@DetailsPanel }
    add(JPanel(BorderLayout()).apply {
      border = JBUI.Borders.customLineBottom(JBColor.border())
      add(left.component, BorderLayout.WEST)
      add(right.component, BorderLayout.EAST)
    }, BorderLayout.NORTH)
    body.add(ScrollPaneFactory.createScrollPane(record, true), Tab.RECORD.name)
    body.add(value, Tab.VALUE.name)
    add(body, BorderLayout.CENTER)
    border = JBUI.Borders.customLine(JBColor.border(), 0, 1, 0, 0)
  }

  fun select(next: Tab) {
    tab = next
    cards.show(body, next.name)
    update()
  }

  fun update() {
    val (row, column) = table.currentCell() ?: return
    val shown = table.view
    record.removeAll()
    table.visibleColumns().forEach { c ->
      record.add(plainLabel().apply { text = shown.columns[c] })
      record.add(JBTextField(if (shown.kinds[row][c] == CellKind.NULL) NULL_TEXT else shown.cells[row][c]).apply {
        isEditable = false
        font = table.font
      })
      record.add(Box.createVerticalStrut(JBUI.scale(6)))
    }
    record.revalidate()
    record.repaint()
    value.setFileType(if (shown.kinds[row][column] == CellKind.STRUCTURE) json else text)
    value.text = viewerText(shown, row, column)
  }

  private fun tabAction(name: String, target: Tab) = object : DumbAwareToggleAction(name) {
    init {
      templatePresentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true)
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun isSelected(e: AnActionEvent) = tab == target

    override fun setSelected(e: AnActionEvent, state: Boolean) = select(target)
  }
}

/**
 * The find bar above the grid: matching cells are highlighted, the arrows move to the next match, and
 * **Filter rows** hides the rows without a match.
 */
internal class FindBar(private val table: ResultsTable, private val changed: () -> Unit) : JPanel(BorderLayout()) {
  val field = SearchTextField(false)
  val filterRows = JBCheckBox("Filter rows")

  init {
    isVisible = false
    border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(2, 6))
    field.textEditor.columns = 24
    field.addDocumentListener(object : DocumentAdapter() {
      override fun textChanged(e: DocumentEvent) = apply()
    })
    filterRows.addActionListener { apply() }
    val actions = DefaultActionGroup(
      object : DumbAwareAction("Previous Occurrence", null, AllIcons.Actions.PreviousOccurence) {
        override fun actionPerformed(e: AnActionEvent) {
          table.findNext(field.text, forward = false)
        }
      },
      object : DumbAwareAction("Next Occurrence", null, AllIcons.Actions.NextOccurence) {
        override fun actionPerformed(e: AnActionEvent) {
          table.findNext(field.text, forward = true)
        }
      },
    )
    val close = DefaultActionGroup(object : DumbAwareAction("Close", null, AllIcons.Actions.Close) {
      override fun actionPerformed(e: AnActionEvent) = close()
    })
    val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
      isOpaque = false
      add(field)
      add(ActionManager.getInstance().createActionToolbar("DynoTableResultsFind", actions, true).apply { targetComponent = table }.component)
      add(filterRows)
    }
    add(left, BorderLayout.WEST)
    add(ActionManager.getInstance().createActionToolbar("DynoTableResultsFindClose", close, true).apply { targetComponent = table }.component, BorderLayout.EAST)
    field.textEditor.registerKeyboardAction({ close() }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), WHEN_FOCUSED)
    field.textEditor.registerKeyboardAction({ table.findNext(field.text, forward = true) }, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), WHEN_FOCUSED)
  }

  fun open() {
    isVisible = true
    revalidate()
    field.requestFocusInWindow()
    field.textEditor.selectAll()
  }

  fun close() {
    field.text = ""
    isVisible = false
    revalidate()
    table.requestFocusInWindow()
  }

  fun reset() {
    field.text = ""
    apply()
  }

  private fun apply() {
    table.highlight = field.text
    table.filter = if (filterRows.isSelected) field.text else ""
    changed()
  }
}
