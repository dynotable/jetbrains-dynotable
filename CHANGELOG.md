# Changelog

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
