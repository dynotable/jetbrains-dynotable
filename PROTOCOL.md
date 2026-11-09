# DynoTable Language Server protocol

This document is for authors of editor clients. It describes what goes over the wire between an
editor and the Language Server that the DynoTable desktop app serves. It describes the wire only.

The server speaks the [Language Server Protocol](https://microsoft.github.io/language-server-protocol/)
(LSP) 3.17 over TCP. This document lists what DynoTable adds to LSP and the rules a client must
follow. The `contract/` directory holds JSON examples of the answers and refusals; see
[Fixtures](#fixtures).

## Discovery

The app writes a **port file** while it serves:

| Platform | Path                                                                 |
| -------- | -------------------------------------------------------------------- |
| macOS    | `~/Library/Application Support/DynoTable/lsp-port.json`              |
| Windows  | `%APPDATA%\DynoTable\lsp-port.json`                                  |
| Linux    | `$XDG_CONFIG_HOME/DynoTable/lsp-port.json` (default `~/.config/...`) |

```json
{"port": 41120, "token": "…", "pid": 48213}
```

- `port` is the port the server is bound to. The default preferred port is 41120. When that port
  is taken, the server binds another one, so always read the file.
- `token` is a new random string at every server start. Treat it as an opaque, non-empty string.
- `pid` is the process id of the serving app. An older app does not write it.
- The file has mode `0600`. The app replaces it atomically (write, then rename), so watch the
  directory, not the file.
- The app deletes the file when the server stops cleanly. A killed app leaves the file behind.

**A stale file.** Before you connect, send signal `0` to `pid`. When the call fails (`ESRCH`: no such
process, or `EPERM`: another user's process), the file is stale: do not connect. When the file has
no `pid`, skip the check. When the connect fails with `ECONNREFUSED`, the file is stale too.

The check has limits. A live `pid` shows that the process runs, not that it holds the port. The
server does not prove who it is, so a client sends its token to the process that listens on the
port. The app deletes the file when its server stops, so the time when the port is free and the
file still names it is short. A process of the same user that gets a dead app's `pid` also passes
the check.

## Starting the app

A client that finds no port file, or a stale one, can start the app with no window:

```
<app binary> --headless
```

The process answers through its exit code:

| Result         | Meaning                                                     | What to do                                                                  |
| -------------- | ----------------------------------------------------------- | --------------------------------------------------------------------------- |
| does not exit  | The process serves. It is ready when the port file appears. | Wait for a port file with a new `(port, token)`, then connect.              |
| `0`            | Another instance runs. It was told to start its server.     | Wait about 20 s for the port file. If none appears, start again once.       |
| `1`            | The server could not start.                                 | Report a start failure.                                                     |
| `3`            | The Editor integration is off in the app.                   | Tell the user to switch it on in DynoTable → Settings → Editor integration. |
| any other code | A crash.                                                    | Report a crash.                                                             |

- A process that serves never exits on its own. If it exits with `0` after its port file appeared,
  the user quit the app.
- A headless process has no window. A second launch **without** `--headless` opens a window in the
  running app.
- Some refusals need a window in the app: see `profile-window-required` and `reauth-required` under
  [Errors](#errors).

## Connection and handshake

1. Read the port file once. Use its `port` and `token` together.
2. Open a TCP connection to `127.0.0.1:<port>`. Messages use the LSP base protocol framing
   (`Content-Length` headers).
3. Send `initialize` with these `initializationOptions`:

   ```jsonc
   {
     "token": "<token from the port file>", // required
     "profile": "prod-eu" // optional: a profile id or slug
   }
   ```

4. Send `initialized`.

Rules:

- Finish `initialize` within 30 s. The server closes a connection that does not.
- Until `initialize` succeeds, the server refuses every request with `-32898` and drops every
  notification.
- A wrong or missing token gets `-32898`. The server closes the connection about 250 ms later. Read
  the port file again once (the app may have restarted with a new token), connect again, and give up
  when that fails too.
- `initialize` can also fail with `-32803` and `data.code: "signed-out"`.
- A second `initialize` on one connection is refused. To change the profile, open a new connection.
- `shutdown` and `exit` end that connection only. The app keeps serving.

### Client capabilities the server reads

| Capability                                              | Effect when the client sends it               |
| ------------------------------------------------------- | --------------------------------------------- |
| `window.showMessage`                                    | The server can send `window/showMessage`.     |
| `window.workDoneProgress`                               | A run reports `$/progress` (see [Run](#run)). |
| `textDocument.completion.completionItem.snippetSupport` | Completion can offer snippets.                |

### Server capabilities

`initialize` answers with `serverInfo: {name: "DynoTable", version}` and these capabilities:

- `textDocumentSync`: incremental (`2`);
- completion with resolve. The trigger characters are `.` `'` `"` (not space);
- code actions of kind `quickfix` and the empty kind (`""`);
- code lens (no resolve);
- whole-document formatting;
- hover;
- inlay hints (no resolve);
- definition, document highlight, and rename with prepare. These work on table aliases only;
- `executeCommand` for `dynotable.runStatement`, `dynotable.openInApp` and `dynotable.validateQuery`;
- pushed diagnostics (`textDocument/publishDiagnostics`).

Use `serverInfo.version` to decide which custom requests the server has. The server sends no
`client/registerCapability`, `workspace/applyEdit` or `window/showDocument` request. Rename answers
with its edit.

## Profiles

A session is bound to one DynoTable profile for its whole life.

- With `profile`, the server binds the profile whose id matches, or the one profile whose slug
  matches.
- Without `profile`, a roster of exactly one profile binds that profile.
- In every other case the session starts **unbound**: the profile is not found, two profiles match,
  the profile is incomplete, the roster has more than one profile, or the bind failed.

An unbound session still gives completion of keywords and diagnostics that need no schema. Every
request that needs a profile fails with `profile-required`. Show a picker from
`dynotable/listProfiles`, then open a new connection with the chosen `profile`.

## Languages

| Language id  | Content                                                               |
| ------------ | --------------------------------------------------------------------- |
| `sql`        | DynoTable SQL. It reads DynamoDB tables and OpenSearch indices.       |
| `partiql`    | DynamoDB PartiQL.                                                     |
| `opensearch` | One OpenSearch query: a JSON query DSL body or a Lucene query string. |

The server ignores documents with any other language id.

### The `opensearch` index directive

An `opensearch` document names its index on line 0:

```
// dynotable: index=orders target=prod-search
{"match": {"status": "shipped"}}
```

- Line 0 is a directive when it reads: optional spaces or tabs, `//`, optional spaces or tabs,
  `dynotable:`. The rest of the line is `key=value` tokens separated by whitespace.
- `index` names one index or alias. It cannot contain `*`, `?`, `,`, whitespace or any of
  `\ / " < > | #`, and it cannot start with `.`, `_` or `-`.
- `target` is optional. It is the display name of the OpenSearch target that hosts the index. Add it
  when two targets list the same index name, or when the `dynotable/listIndices` reply has a
  non-empty `unreachable`, a `partial` or an `indexListingFailed`. With no pin, the server refuses
  the query in both cases, because a target that it could not list can also have the name. A pin
  does not help when the pinned target is the one that could not be listed. A display name that
  contains a space cannot be written as a token.
- The server reads the directive line as blank. Every range it sends is a range in the document as
  the client holds it.
- After the directive, a document whose first non-blank character is `{` is a JSON query DSL body.
  Any other document is a Lucene query string.

## Code lens

For `sql` and `partiql`, each statement gets a **Run** lens (`dynotable.runStatement`). It can also
get an **Open in DynoTable** lens (`dynotable.openInApp`). An unbound session gets Run lenses only.
`opensearch` documents get no lenses. A document that does not parse gets no lenses.

The lens's own `range` is a zero-width anchor at the start of the statement. The statement's range
is in the command argument:

```json
{
  "range": {"start": {"line": 1, "character": 0}, "end": {"line": 1, "character": 0}},
  "command": {
    "title": "Run",
    "command": "dynotable.runStatement",
    "arguments": [
      {
        "uri": "file:///q.dynotable.sql",
        "range": {"start": {"line": 1, "character": 0}, "end": {"line": 1, "character": 29}}
      }
    ]
  }
}
```

## Commands

All three commands take one argument object:

```ts
interface CommandArgs {
  uri: string; // required
  range?: Range; // an LSP range; without it, the command uses the whole document
  rowCap?: number; // runStatement only
  index?: string; // opensearch only: overrides the directive's index
  target?: string; // opensearch only: goes with `index`
  seedStatement?: boolean; // openInApp, sql only: also open the statement in the app's editor
}
```

An `index` argument never combines with the directive's `target`. Send its `target` with it.

### Run

`dynotable.runStatement` answers a `RunResult`:

```ts
interface RunResult {
  columns: string[];
  rows: Array<Record<string, unknown>>;
  returnedRows: number;
  totalRows: number | null; // null: the total is unknown
  truncated: boolean;
  itemsScanned?: number; // sql only
  approximateBytes: number;
  elapsedMs: number;
  executedStatement: string; // what ran; for opensearch, the request body as JSON
  effectiveRowCap: number;
}
```

- **Row cap.** `rowCap` defaults to 500. The server clamps it to `[1, 1000]` and reports the value
  it used in `effectiveRowCap`.
- **Totals.** `totalRows: null` means the total is unknown. Do not show `returnedRows` as the total.
  `truncated: true` can come with an exact `totalRows`.
- **Columns.** Show `columns` as returned.
  - A qualified column such as `o.orderId` is a literal key of each row, not a path. It keeps its
    spelling.
  - An unqualified, unquoted SQL identifier comes back in lower case: `GROUP BY customerId` answers
    the column `customerid`.
  - A `sql` projection that names its columns answers them in the order of the query.
  - A `sql` `SELECT *` answers one column per attribute, sorted. Over a join, these are
    `<alias>.<attribute>` keys, sorted.
  - For `partiql` and `opensearch`, `columns` is the union of the row keys in first-seen order. A
    row can lack a key.
- **Values (`sql` and `partiql`).** Both dialects answer the same shapes:
  - A string, a boolean and `null` come as JSON values.
  - When an item does not have an attribute that a `sql` projection names, the cell is `null`. A
    `SELECT *` row and a `partiql` row do not have the key.
  - A number comes as a JSON number. A number that JSON cannot hold exactly comes as `{"N": "<exact
    decimal>"}`.
  - Binary comes as `{"B": "<base64>"}`.
  - A Set comes as a one-key object that names its type: `{"SS": [...]}`, `{"NS": [...]}` or
    `{"BS": [...]}`. Each `NS` member is its decimal text (a string), and each `BS` member is base64.
    The order of the members is not fixed.
  - A list comes as an array, and a map as an object. A Set or binary value inside them has the same
    form.
  - A string that looks like JSON stays that string.
  - A projected column (`SELECT tags`, `SELECT (tags)`) answers the same value as `SELECT *`.
  - Exception: a `sql` statement that the server cannot compile (for example chained joins or window
    functions) can answer a column whose cells mix strings and structures as JSON text.
- **Values (`opensearch`).** A row is the hit's `_source`, as stored, plus its `_id`.
- **Empty.** An empty result can have `columns: []`.
- **opensearch.** A run with no `range` runs the whole document. A `range` whose text is blank is
  refused.
- **Progress.** When the client sent `window.workDoneProgress`, the server sends
  `window/workDoneProgress/create`, then one `$/progress` `begin` (with a `title`,
  `cancellable: false`), then one `$/progress` `end` with the same token. `end` comes on every
  exit, refusals included. There is no `report`.
- **Cancel.** Send `$/cancelRequest` for the run's request id. A cancelled run fails with
  `run-failed` and the message `The run was cancelled before it finished.`, in every dialect, also
  when the cancel arrives before the run starts. A run that finishes after the cancel still fails;
  it never answers with rows. Closing the connection also stops a run.

### Open in DynoTable

`dynotable.openInApp` answers `null`. It opens the app at the statement's table or index. For SQL,
that is the first `FROM` operand. An `opensearch` document is always refused. While no app window
is open on the session's profile and region, the command fails with `profile-window-required`.

### Validate against the index

`dynotable.validateQuery` applies to `opensearch` documents with a JSON body. The server asks the
index whether the query parses. Nothing runs. The answer is a sentence. When the client sent
`window.showMessage`, the server also sends the same sentence as `window/showMessage`.

## Custom requests

All three need an initialized session. The last two also need a bound one.

### `dynotable/listProfiles`

No parameters. Answers an array:

```ts
interface ProfileRow {
  id: string;
  name: string;
  slug: string;
  kind: 'online' | 'offline';
  bindable: boolean;
  reason?: 'incomplete'; // why the row cannot bind
  engines: {dynamodb: boolean; opensearch: boolean};
}
```

### `dynotable/listTables`

Parameters: `{fresh?: boolean}`. Answers:

```ts
interface ListTablesResult {
  tables: string[]; // every table in the bound region
  tableListingFailed?: {message: string}; // only when the listing failed
}
```

### `dynotable/listIndices`

Parameters: `{fresh?: boolean}`. Answers:

```ts
interface ListIndicesResult {
  indices: Array<{name: string; target: string}>;
  unreachable: Array<{target: string; message: string}>; // targets whose listing failed
  indexListingFailed?: {message: string}; // only when the targets could not be listed
  partial?: {message: string}; // only when the list of targets is incomplete
}
```

- `target` is a display name. An index on two targets is two rows.
- Two targets with the same display name cannot be told apart.

For both listings, `fresh: true` reloads the listing first. Any other parameters read the listing
the session holds. A failed listing answers with what the session still holds, plus the failure. A
profile with that engine off answers empty lists and no failure.

An app that is too old for a request answers `-32601` (method not found). Read that as "update
DynoTable", not as an empty listing.

## Errors

| Code     | Meaning                                                                      |
| -------- | ---------------------------------------------------------------------------- |
| `-32898` | Authentication failed, or a request came before `initialize`. Not a session. |
| `-32803` | A typed refusal. `data.code` says which. Show `message` to the user.         |
| `-32601` | The app does not have this request.                                          |

The `-32803` codes:

| `data.code`               | Meaning                                                                      |
| ------------------------- | ---------------------------------------------------------------------------- |
| `profile-required`        | The session is unbound. Let the user pick a profile and reconnect.           |
| `profile-window-required` | The command needs an app window on the profile. The user must open one.      |
| `reauth-required`         | The AWS sign-in expired. Only the app can renew it, in a window.             |
| `premium-required`        | The feature needs an active licence.                                         |
| `signed-out`              | The app has no licence. The app also ends every session when this happens.   |
| `stage-in-app`            | A PartiQL write. Offer to open it in DynoTable, where the user can stage it. |
| `busy`                    | The app is running another query. Try again later.                           |
| `planner-refused`         | The query is too large for this device.                                      |
| `run-failed`              | Any other failure. `message` says what went wrong.                           |

The list of codes can grow. Treat an unknown code as `run-failed`.

## Fixtures

`contract/` holds one JSON file per answer or refusal. `contract/manifest.json` gives each file its
shape and its provenance:

- `authored`: written from this document;
- `recorded`: captured from a running app, with profile names, slugs, ids, target names and
  endpoint hosts replaced by placeholders.

Some shapes stay `authored` because a healthy local app does not produce them, or recording them
would sign the app out: `indexListingFailed`, `partial`, `unreachable` targets, an `incomplete`
profile and the `signed-out` refusal. No fixture holds `tableListingFailed`, `busy`,
`premium-required`, `reauth-required`, `planner-refused` or `-32601`.
