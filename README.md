# DynoTable for JetBrains IDEs

Write SQL, PartiQL and OpenSearch queries in JetBrains IDEs. The [DynoTable](https://dynotable.com)
desktop app serves the language features and runs the queries.

- Completion from your real table schemas and index mappings.
- Diagnostics with quick fixes, hover, the query plan above each statement, formatting, and
  rename of table aliases.
- **Run** a statement. The rows show in a results tool window.
- An explorer of the tables and indices of your DynoTable profile.
- **Open in DynoTable** opens the app at the statement's table or index.

Full guide: [dynotable.com/docs/editors](https://dynotable.com/docs/editors).

This plugin is in development. It has no release yet.

## Examples

A SQL JOIN, in `orders.dynotable.sql`:

```sql
SELECT o.orderId, o.status, c.country FROM "orders" o JOIN "customers" c ON o.customerId = c.customerId
```

A DynamoDB PartiQL query, in `orders.partiql`:

```sql
SELECT * FROM "orders" WHERE customerId = 'CUST010'
```

An OpenSearch query DSL search, in `orders.opensearch`. The first line names the index:

```
// dynotable: index=orders
{"query": {"match": {"status": "shipped"}}}
```

## Requirements

- A JetBrains IDE with language server support, version 2026.3 or later: IntelliJ IDEA, DataGrip,
  WebStorm, PyCharm, GoLand, Rider and others.
- A trusted project.
- The DynoTable desktop app, version 1.19.0 or later, installed on the same computer.
- The Editor integration switched on in the app. **It is off by default.**

### Switch on the Editor integration

1. Open DynoTable.
2. Go to **Settings → Editor integration**.
3. Switch on **Editor integration**.

You do this one time. After that, the plugin starts DynoTable when it needs the app.

## Query files

| File              | File type            | Language                                 |
| ----------------- | -------------------- | ---------------------------------------- |
| `*.dynotable.sql` | DynoTable SQL        | DynoTable SQL (tables and indices)       |
| `*.partiql`       | DynoTable PartiQL    | DynamoDB PartiQL                         |
| `*.opensearch`    | DynoTable OpenSearch | One OpenSearch query: JSON DSL or Lucene |

DynoTable serves these files also when they are scratch files or outside the project.

DynoTable does not attach to other `.sql` files. To attach one, open it and run
**Tools › DynoTable › Attach SQL File to DynoTable**. The action adds the file to the **SQL files**
setting of the project. Only a `.sql` file in the project can attach. Each open project has its
own **SQL files** setting.

An OpenSearch query names its index on its first line:

```
// dynotable: index=orders
{"query": {"match": {"status": "shipped"}}}
```

When two OpenSearch targets have an index with the same name, add `target=<target name>`. Also add
it when DynoTable could not list the indices of all targets. **Pick OpenSearch Index** and the
explorer's New Query do this for you. A target name that contains a space cannot be written there.

## Use it

All actions are in **Tools › DynoTable** and in **Find Action**.

- **New query.** Run **New DynoTable Query** and choose SQL, PartiQL or OpenSearch. The query opens
  as a scratch file. In the explorer, double-click a table or an index, or press Enter, to open a
  query for it. Its context menu also has **New DynoTable Query**.
- **Run.** Click the **Run** lens above a statement, press Cmd+Enter (Ctrl+Enter on Windows and
  Linux), or run **Run DynoTable Query**. Run uses the selection. With no selection, it uses the
  statement at the caret, or else the whole file. An OpenSearch query runs the selection or the
  whole file.
- **Results.** The **DynoTable Results** tool window shows the rows of the last run. It shows the
  total only when DynoTable knows it. A run returns up to 500 rows. The **Copy All Rows** menu in its
  toolbar copies the rows as CSV or JSON. **Cancel Run** (the stop icon) stops the run. Closing the results tab also stops it. You can run one
  statement at a time.
  - Click a column header to sort. Drag a column header to move the column.
  - Type in the grid to find a cell. The **Filter rows** field hides the rows that do not contain
    its text.
  - Double-click a cell, or press Shift+Enter, to see its full value.
  - Right-click for **Copy Value**, **Copy Selection as CSV**, **JSON** or **Markdown**, **Copy
    Column**, **Record View** (one row as a list of columns and values), **Hide Column** and
    **Show All Columns**.
- **Open in DynoTable.** Click the **Open in DynoTable** lens, or press Cmd+Shift+Enter
  (Ctrl+Shift+Enter) with the caret in the statement. SQL and PartiQL only.
- **Shortcuts.** In DynoTable files, Cmd+Enter and Cmd+Shift+Enter run the DynoTable actions before
  the IDE's own actions on these keys (for example Split Line, Complete Current Statement, and
  Execute in DataGrip). In other files, the keys keep their usual actions. Change them in
  **Settings › Keymap**.
- **Explorer.** The **DynoTable** tool window shows your DynamoDB tables and, for each OpenSearch
  target, its indices. **Refresh DynoTable Explorer** reads the lists again.
- **Pick index.** In an OpenSearch query, run **Pick OpenSearch Index** to write the index line.
- **Validate against the index.** In an OpenSearch JSON query, this intention action asks the index
  if the query is valid. Nothing runs.
- **PartiQL writes** do not run from the editor. **Stage in DynoTable** opens the statement's table
  in the app. The statement stays in the editor.
- **Open Documentation.** Run **Open DynoTable Documentation** to open the full guide.

## Profiles

A connection uses one DynoTable profile.

- When DynoTable has one profile, the plugin uses it.
- When it has more, run **Select DynoTable Profile**. While no profile is selected, a click on the
  DynoTable status bar widget also offers it. The IDE keeps your choice for this project, on this
  computer only.

The status bar widget shows the profile you selected. Click it to see what to do when DynoTable is
not connected. **Reconnect DynoTable** connects again.

## DynoTable runs in the background

When DynoTable is not running, the plugin starts it with no window. The app then keeps running
after you close the IDE, and the next IDE window uses it again.

- **Get a window back:** open DynoTable as usual (Dock, Start menu or app launcher), or click
  **Open DynoTable** in a DynoTable message.
- Some actions need an app window: signing in to DynoTable, an expired AWS sign-in, and **Open in
  DynoTable** on a profile with no open window.
- **macOS:** a permission prompt from DynoTable can name your IDE. This is because the IDE started
  the app.
- **Linux:** the plugin cannot read the app version before it starts the app. An app that is too
  old opens a window one time. An AppImage install needs the **App path** setting.
- If you updated DynoTable but did not restart it, the old app can open a window one time. Restart
  DynoTable.

## Free and paid

The plugin is free. What runs depends on your DynoTable licence, as in the app:

- **Free (signed in):** completion, diagnostics, PartiQL `SELECT`, and OpenSearch queries.
- **Trial or paid:** running SQL statements, as in the SQL Workbench. This includes SQL that
  reads an OpenSearch index and a JOIN across DynamoDB and OpenSearch.
- **Signed out:** nothing. Sign in to DynoTable.

See [Activation](https://dynotable.com/docs/activation) for the licence states.

## Settings

Go to **Settings › Tools › DynoTable**.

| Setting             | Scope                                 | Default | Description                                                                         |
| ------------------- | ------------------------------------- | ------- | ----------------------------------------------------------------------------------- |
| SQL files           | This project (`.idea/dynotable.xml`)  | empty   | Glob patterns of `.sql` files, relative to the project folder. DynoTable serves the `.sql` files that match. |
| App path            | This computer                         | empty   | The path of the DynoTable app. Set it when the app is not in its standard location. |
| User data directory | This computer                         | empty   | The app's user data directory. When set, the plugin does not start the app.         |

The **SQL files** setting is in the project's `.idea` folder, so you can share it with your team.
The IDE does not sync **App path** and **User data directory** to other computers.

## Privacy

The plugin connects only to the DynoTable app on your computer (`127.0.0.1`). It reads the
connection token from the app's port file and does not store it.

The IDE shows all language server traffic in the **Services** tool window (the LSP console). This
traffic includes the token, on each connection. When you switch on debug logging for language
services, the IDE's language services logs also contain it. DynoTable makes a new random token each time the app starts.
The token is valid only on `127.0.0.1`.

## Platforms

Tested on macOS. Windows and Linux are not verified yet.

These are not supported:

- Remote development (JetBrains Gateway, JetBrains Client) and projects in WSL. DynoTable serves only
  files on this computer.
- Light Edit mode.
- Android Studio: it has no language server support.

## For client authors

[PROTOCOL.md](PROTOCOL.md) describes the wire protocol of the DynoTable language server.
[CONTRIBUTING.md](CONTRIBUTING.md) tells how to build and test this plugin.
[docs/publishing.md](docs/publishing.md) tells how to release it.

## Licence

MIT. See [LICENSE](LICENSE).
