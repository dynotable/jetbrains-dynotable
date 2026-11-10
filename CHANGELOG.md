# Changelog

## Unreleased

- DynoTable Results is a data grid:
  - Numbers align right. `null` and nested values are dimmed, and a nested value shows in full in
    its tooltip.
  - Rows are striped and numbered. Columns fit their content, and you can resize and move them.
  - Click a column header to sort. Numbers sort by value.
  - Type in the grid to find a cell. The filter field hides the rows that do not contain its text.
  - Double-click a cell, or press Shift+Enter, to see its full value. JSON shows formatted, with
    Copy.
  - The context menu has Copy Value, Copy Selection as CSV, JSON or Markdown, Copy Column, Record
    View, Hide Column and Show All Columns. Cmd+C (Ctrl+C) copies only the columns that show.
- DynoTable Results has a toolbar: Cancel Run, and a Copy All Rows menu (CSV or JSON). It no longer
  shows the statement text above the rows.

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
