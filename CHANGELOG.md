# Changelog

## Unreleased

- DynoTable Results: the value viewer indents a string that holds JSON without changing its text. A
  large integer, `1.0` and a `\u` escape stay as they are.

## 0.1.2 (2026-11-11)

- DynoTable Results: a Find match in the row of the selected cell keeps its highlight. The "N of M rows
  shown" note lines up with the toolbar. The status bar shows the selection totals only while the
  grid has the focus, as the IDE's own data grids do.

## 0.1.1 (2026-11-11)

- DynoTable Results is a data grid that looks and works like the IDE's own data grids:
  - Cells use the editor font. Numbers align right, and `null` shows as `<null>`. Columns fit their
    content, and you can resize and move them. Rows are numbered.
  - Select a cell, a block of cells, rows (click the row numbers) or a column (click its name). Cmd+C
    (Ctrl+C) copies one cell as its value, and more cells as tab-separated text.
  - The status bar shows the count, sum, average, minimum and maximum of the selected number cells.
  - Click the sort icon in a column header to sort. Numbers sort by value. Missing and `null` values
    sort last in both directions.
  - Click the filter icon in a column header to keep only some of its values.
  - Find (Cmd+F) highlights the matching cells and moves between them. Its Filter rows option hides
    the rows without a match.
  - A side panel shows the full value of a cell (Open in Value Editor, Shift+Enter or a double-click)
    or the row as columns and values (Show Record View, Shift+Cmd+Enter). JSON shows formatted.
  - The cell menu has Copy Value and Copy As CSV, JSON or Markdown. The header menu has Copy Column,
    Pin Column, Hide Column, Hide Other Columns, Show All Columns and sorting.
- DynoTable Results has a toolbar: Cancel Run, a Copy All Rows menu (CSV or JSON), Export… (CSV, JSON
  or Markdown, to a file), Find, Show Record View and Open in Value Editor. It no longer shows the
  statement text above the rows.
- The plugin has its own icon, in Settings › Plugins and on the Marketplace.
- The DynoTable tool window icons turn white when their tool window is active, as the IDE's own do.

## 0.1.0

First release. Needs a JetBrains IDE 2026.3 or later and DynoTable 1.19.0 or later.

- Language features from the DynoTable app for `*.dynotable.sql`, `*.partiql` and `*.opensearch`
  files: completion, diagnostics with quick fixes, hover, formatting and alias rename. Scratch
  files and files outside the project get them too.
- The query plan shows in the lens row above the statement, after Run and Open in DynoTable.
- Run a statement with the Run lens or Cmd+Enter (Ctrl+Enter). The rows show in the DynoTable
  Results tool window, with Copy CSV, Copy JSON and Cancel.
- Open in DynoTable with its lens or Cmd+Shift+Enter (Ctrl+Shift+Enter). Change the shortcuts in
  Settings › Keymap.
- Validate against the index, and Stage in DynoTable for PartiQL writes.
- The DynoTable tool window: an explorer of the profile's DynamoDB tables and OpenSearch indices,
  with Refresh. A double-click on a table or an index opens a query for it.
- New Query, Pick OpenSearch Index, Attach SQL File, Select Profile, Reconnect and Open
  Documentation actions, in Tools › DynoTable.
- A status bar widget shows the profile, or why DynoTable is not connected and what to do.
- The SQL files, App path and User data directory settings, in Settings › Tools › DynoTable.
- Starts DynoTable in the background when it is not running.
