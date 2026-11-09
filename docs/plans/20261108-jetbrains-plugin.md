# DynoTable for JetBrains IDEs

## Overview

- A JetBrains plugin with the features of vscode-dynotable 0.1.1: SQL, PartiQL and OpenSearch
  queries in the editor, served by the DynoTable desktop app over its LSP server (TCP).
- Target: JetBrains IDEs with `com.intellij.modules.lsp` (IntelliJ IDEA, DataGrip, WebStorm,
  PyCharm, GoLand, Rider and others). Android Studio, remote development, WSL and Light Edit are
  out of scope (VS Code 0.1.1 also does not support remote workspaces).
- Minimum IDE version: **2026.3** (decided 2026-11-08). Up to 2026.2.x, the platform LSP client
  serves only files in project content (`LspClientImpl.isSupportedFile` checks
  `ProjectFileIndex.isInContent`; verified at `idea/2026.1.4` and `idea/2026.2.2`). At
  `idea/2026.3-rc` that check is gone from `isSupportedFile` and `processOpenedFiles`, but
  `LspClientManagerImpl.startIfNeeded` (used by `startClientsIfNeeded` and
  `stopAndRestartClientsIfNeeded`) still has it, so the plugin never restarts through those two
  calls.
- New public repository `dynotable/jetbrains-dynotable` (Kotlin, IntelliJ Platform Gradle Plugin
  2.x). It holds a copy of `PROTOCOL.md`, `contract/` and the two grammars from vscode-dynotable.
  A CI job fails when the copies differ from vscode-dynotable `main`.
- The app does not change. App 1.19.0 or later, enforced by the plugin.

## Context (from discovery)

- Wire protocol: `PROTOCOL.md`.
- vscode-dynotable modules whose behaviour this plugin re-writes in Kotlin (behaviour only):
  - `src/discovery.ts`: port-file paths, port-file parse, app locator (macOS `Info.plist`,
    Windows Squirrel `app-*`, Linux), `meetsMinimum`, `MIN_APP_VERSION = 1.19.0`.
  - `src/connect-core.ts`: `EXIT_RESULTS` (`1` → `start-failed`, `3` → `integration-off`),
    `launchEnv` (allow-list on macOS/Linux, deny-list on Windows), spawn options (detached, stdio
    ignored, cwd home), timeouts (30 s start, 20 s after exit 0, 40 s `initialize`), latch, restart
    rules (`waiting-for-run`, restart storm), auth retry, `ECONNREFUSED` → stale, `ECONNREFUSED`
    after a launch → `start-failed`, version gate, `lastKey` echo guard, recheck after a latch.
  - `src/reasons.ts`, `src/status.ts`: the latched reasons and the actions of each.
  - `src/refusals.ts`: refusal → action; the server's `message` is the text; a cancel the client
    asked for is silent; a request the closed connection dropped is silent; a client that is not
    running → the status reason.
  - `src/plan-lens.ts`: plan placement in the lens row.
  - `src/results-model.ts`: `summary()`, `cellText` (string as is, all else compact JSON),
    `toCsv()` with the formula guard, `toJson()`.
  - `src/profiles.ts` (`pickerOf`), `src/explorer-model.ts`, `src/directive.ts`, `src/attach.ts`,
    `src/commands.ts` (run target and its guards, Open and its guards, Stage, Pick Index, lazy
    connect through `deps.client()`).
  - `src/client.ts`: refused server-to-client requests, blocked trace channel.
  - `src/test-integration/fake-server.ts`, `live-app.ts` and every `*.test.ts` (including
    `media/results.test.ts`): the reference regression suite.
- Fixtures: `contract/` holds 27 files (26 fixtures + `manifest.json`).
- Grammars: `syntaxes/partiql.tmLanguage.json`, `syntaxes/opensearch.tmLanguage.json`.
- JetBrains LSP API (intellij-community, checked at `idea/2026.1.4` and `idea/2026.3-rc`):
  - `LspCommunicationChannel.Socket(port, startProcess = false)` connects to
    `InetAddress.getLoopbackAddress()`.
  - `LspIntegrationProvider.fileOpened` (read lock, must stay fast) →
    `LspClientStarter.ensureClientStarted`. EP `com.intellij.platform.lsp.integrationProvider`.
  - `LspClientDescriptor`: `createInitializationOptions()`, `getLanguageId(file)`,
    `clientCapabilities`, `lsp4jServerClass: Class<out Lsp4jServer>`, `createLsp4jClient`,
    `lspCustomization`, `lspServerListener`, `lspCommunicationChannel`.
  - `LspClientManager.getInstance(project)`: `ensureClientStarted(providerClass, descriptor)`
    (only launches a coroutine), `stopClients`, `getClients`. `addListener` is internal.
  - A failed client stays registered as `ShutdownUnexpectedly` (red "Terminated" in the Language
    Services widget, with Restart). At 2026.3-rc the platform Restart calls `restartClient` with
    the same descriptor.
  - `LspServerListener`: `serverInitialized(InitializeResult)` (called under `connectorLock`,
    before `didOpen` of open files; must not block) and `serverStopped(shutdownNormally)`. The
    `initialize` error itself is only logged. Platform start timeout `lsp.server.connect.timeout`
    = 100 s.
  - `LspCustomization`: `inlayHintCustomizer = LspInlayHintDisabled`, `codeLensCustomizer`
    (`LspCodeLensSupport.codeLensClicked(LspClient, VirtualFile, Command, MouseEvent?)`),
    `commandsCustomizer` (`LspCommandsSupport.executeCommand` sends a notification by default, so
    the answer and any error are lost). `refreshCodeLenses` refreshes only the platform's own
    Code Vision provider.
  - `LspClient.sendRequest { … }` (suspend): the code throws on an error answer and has no timeout
    (its KDoc says null and 10 s; trust the code, pin it in the spike). It returns `null` when the
    client is not running. `sendRequestSync` has a 10 s timeout and returns null on error.
  - Errors arrive as `org.eclipse.lsp4j.jsonrpc.ResponseErrorException` (`.responseError`).
  - `executeCommand` returns `CompletableFuture<Object>`: lsp4j decodes the answer to
    `LinkedTreeMap` with `Double` numbers. The platform's Gson only adds `disableHtmlEscaping`.
  - `isSupportedFile` results are cached per client and must not depend on settings.
    `FileTypeOverrider.getOverriddenFileType(VirtualFile)` gets no project; its results are cached
    until `FileTypeManagerEx.makeFileTypesChange`.
  - `Lsp4jClient.applyEdit`, `showDocument`, `registerCapability` are `final`, but `Lsp4jClient`
    takes an `LspServerNotificationsHandler`: `createLsp4jClient` can wrap the handler.
  - The platform prints all LSP traffic to the Services LSP console (always) and, with debug
    logging, to `<log>/language-services/*.log`. This traffic includes `initializationOptions.token`.
  - The platform refuses to start LSP in an untrusted project.
  - TextMate EP `com.intellij.textmate.bundleProvider` takes `PluginBundle(name, path)`: a bundle
    directory with a manifest on the file system.
  - Test helpers: package `com.intellij.platform.lsp.testFramework` at 2026.3
    (`awaitFileOpenedByLspServer`, `awaitDiagnosticsFromLspServer`).
  - Default keymaps: Cmd/Ctrl+Enter is Split Line (and Execute in Database Tools / DataGrip);
    Cmd/Ctrl+Shift+Enter is Complete Current Statement.

## Development Approach

- **testing approach**: Regular (code first, then tests in the same task).
- Complete each task fully before the next. Small, focused changes.
- Every task with code changes adds or updates tests. Tests follow the user's Rule 8: each test
  names a real bug that no other test catches. No tautological, redundant or subset tests.
- All tests pass before the next task. Update this plan when the scope changes.
- Repository rules (copy them into the new repo's `CLAUDE.md` and `CONTRIBUTING.md`):
  - The repository is public. Write only from `PROTOCOL.md` and the plugin itself. Copy nothing
    from the private app's code or docs. Do not name the app's modules, files or internals.
  - Never point anything at the real app user data dir. Tests never start the real app and never
    read the real port file: in test mode, spawn and open-external throw unless a fake binary is
    set; a test app root replaces every install location; a test user-data dir replaces the
    port-file directory (and, unlike the `userDataDir` setting, keeps the launcher on).
  - Docs in ASD-STE100 Simplified Technical English.

## Testing Strategy

- **Unit** (JUnit 5, no platform import): pure rules, including the pure connect core with an
  injected clock, start and dispose (the `connect-core.test.ts` race cases).
- **Integration** (headless platform tests, `com.intellij.platform.lsp.testFramework` helpers): a
  Kotlin fake server on a `ServerSocket` with the lsp4j launcher answers from `contract/`. Each fake
  behaviour is added with the first test that needs it. UI seams: every notification captured
  (the plugin's and the platform's, with a positive control that a server `showMessage` is
  counted), a way to click a notification action, scripted popup answers, the results and explorer
  models read directly. Fallback when the spike shows the LSP API does not run headless:
  Starter/Driver in a real IDE under Xvfb in Docker, with the test seams switched on by a system
  property that only the test launcher sets.
- **Live** (`@Tag("live")`, local only, `./gradlew testLive`): the app through a wrapper as in
  `live-app.ts`: a prepared user data dir from `DYNOTABLE_LIVE_USER_DATA_DIR` (integration on,
  signed in, more than one profile), `DYNOTABLE_LIVE_PROFILE`, `DYNOTABLE_LIVE_LARGE_TABLE`,
  `--user-data-dir`, `--use-mock-keychain`, the deny-dir guard against the real data dir.
  Spike result (Task 1 incident): never a build that can update itself. A packaged release's updater
  relaunches the app without the wrapper flags, on the real data dir. Use a dev build whose wrapper also
  sets the app's user data directory override, and prove isolation before the first test (`lsof`: no open file under the real
  data dir; the port file appears only in the test dir).
- **verifyPlugin**: IntelliJ IDEA and DataGrip, at the minimum version and at the latest release.
- **Reference regression map.** Each vscode test below guards a real bug. Each one gets a Kotlin
  counterpart in the named task, or a one-line reason in that task why it does not apply:

  | vscode test file | Task |
  | --- | --- |
  | `protocol.test.ts`, `contract.test.ts` | 4 |
  | `manifest.test.ts` (grammar paths, settings scope, trust, engines floor, no server command id contributed) | 2, 5, 9 |
  | `discovery.test.ts` | 6 |
  | `connect-core.test.ts` | 6, 7, 9 |
  | `reasons.test.ts` | 7, 9 |
  | `connect.test.ts` (the showMessage counting control too) | 9, 10 |
  | `no-app.test.ts` | 9, 10 |
  | `restored.test.ts`, `no-folder.test.ts` | 9 |
  | `workspace-folders.test.ts` | 9, 16 |
  | `refusals.test.ts` | 10 |
  | `run.test.ts` | 10, 11, 12, 13 |
  | `results-model.test.ts`, `media/results.test.ts` | 11 |
  | `plan-lens.test.ts` | 13 |
  | `profiles.test.ts` | 14 |
  | `explorer.test.ts`, `explorer-model.test.ts`, `directive.test.ts` | 15 |
  | `editor-commands.test.ts` | 15, 16 |
  | `attach.test.ts` | 16 |
  | `live*.test.ts` | 17 |

  Not applicable (reason): `contract.test.ts` "leaks" and "scrub" (they test the recorder, which
  stays in vscode-dynotable); `results-model.test.ts` webview HTML nonce and panel messages, and
  `media/results.test.ts` "asks the host for the current view" (webview only; the Swing tool
  window has no message channel); `connect.test.ts` "refused server requests" when spike point 8
  shows the handler cannot be wrapped (then a residual risk).

## Progress Tracking

- Mark completed items with `[x]` immediately.
- Add new tasks with ➕. Document blockers with ⚠️.
- Keep the plan in sync with the work.

## Solution Overview

- **Connect core (plugin-owned).** The platform owns the LSP session but hides why `initialize`
  failed. So a project service `ConnectCore` owns discovery, launch, a **preflight** and the
  status. Its rules live in a pure `ConnectMachine` (injected clock, spawn, connect, start and
  stop) with unit tests; the service is thin glue.
  1. Read the port file, stale check, version check, `--headless` spawn with launch hygiene, exit
     codes, timeouts. A second spawn from another project exits `0` and waits, per PROTOCOL.md.
  2. Preflight: the plugin's own socket to the same address the platform uses, `initialize` with
     the token only (no `profile`, so the server binds once), then `shutdown`/`exit`. It maps:
     `-32898` → reread once, then `auth-failed`; `signed-out` → latch; other `initialize` error →
     `start-failed`; closed → `connection-ended`; `ECONNREFUSED` → stale → launch (after a launch
     → `start-failed`); other connect error → `start-failed`; no answer in 40 s → `timed-out`;
     `serverInfo.version` below 1.19.0 or missing → `update-app`.
  3. Start: `stopClients`, then `ensureClientStarted` with a **new descriptor per attempt**. The
     descriptor holds the preflight's `(port, token)` (never reads the port file itself); only the
     profile is read lazily. An attempt is in flight until the client whose descriptor is that
     object shows in `getClients` and reports `serverInitialized` or `serverStopped`. A deadline
     covers an attempt that never reports (the platform drops a start with no signal when a
     client with the same server id exists, or at its 10-client limit): 10 s to show in
     `getClients`, counted from when `stopClients` returned and not while a modal dialog is open
     → `start-failed`; 40 s to report (the server must answer `initialize` in 30 s) →
     `timed-out`; both call `stopClients`. Restart requests during an attempt queue into one
     later restart.
  4. `serverInitialized` (from the current descriptor): launch, without blocking, the connect-time
     `dynotable/listTables` (`profile-required` → `unbound`; `-32601` → `missing-request`, only
     here; `signed-out` → stays `connected`, the app ends the session). From any other
     descriptor: nothing while a current attempt exists (the platform is already stopping that
     client, and `stopClients` would kill the current one); `stopClients` only while latched.
  5. Only the current descriptor counts. **Restart kinds:** a plugin restart (profile change,
     `sqlFiles` change, Reconnect, new port file) first waits for a running query
     (`waiting-for-run`), then **retires** the current descriptor and stops its client. A latch
     and a stop the plugin did not ask for retire at once and fail (cancel) the in-flight run.
     `serverStopped` and `serverInitialized` from a retired or unknown descriptor change nothing.
     From the current descriptor, a stop the plugin did not ask for:
     - while the project or `ConnectCore` is closing or disposing (project close, IDE exit,
       plugin unload) → nothing; no preflight, no start;
     - before `serverInitialized` → `start-failed`;
     - after, `shutdownNormally = true` (platform Restart or Stop; they look the same here, see
       Decision 3) → `disconnected(stopped)`, not latched; a later `serverInitialized` from the same,
       not retired descriptor (the platform Restart) → `connected`; else the next trigger connects
       again; action Reconnect;
     - after, `shutdownNormally = false` (the app quit or the socket closed) → `connection-ended`. On every latch: `stopClients`, so no red "Terminated"
     client with a Restart stays. Nothing starts again until Reconnect or a new port file
     (`lastKey` echo guard; a new `(port, token)` during an attempt reconnects once the attempt
     latches).
  6. `stopClients` runs on a background job that may block (a graceful stop waits up to 10 s; a
     stop during `initialize` waits for it), never inside a listener callback or on the
     `ConnectMachine` thread.
  7. "Connected" for a command means the target file is open on the server (its `didOpen` sent),
     not only `serverInitialized`: the platform sends `didOpen` after `serverInitialized`. How to
     observe it: Decision 6. The wait has a 10 s deadline (the platform can skip a file for
     good: an untrusted file, a closed tab, a cached `isSupportedFile = false`); then the command
     shows "DynoTable has not opened this file. Close and open the file, then try again." and
     does nothing.
  - Statuses: `hidden | starting | connected | unbound | disconnected(reason)`, with the reasons
    of `reasons.ts` plus `untrusted` and `stopped`, and the actions of each.
  - Triggers: `fileOpened` (only schedules; never blocks), a restored editor, showing the
    explorer, every command (lazy connect: a command waits in a background task for the first
    connect or the in-flight attempt; "DynoTable is reconnecting. Try again shortly." only while a
    restart runs; the reason once when latched), Reconnect, a new port file, a project becoming
    trusted. Untrusted project → no launch, no preflight, status `untrusted` (not latched; sentence
    "DynoTable does not run in an untrusted project."; actions Trust Project, Open Documentation).
    A command in an untrusted project shows that sentence and its actions once, at once. Trust
    Project opens the platform's own trust dialog (with its warning) and never calls
    `setProjectTrusted` itself.
- **Editor features** come from the platform: completion, diagnostics, quick fixes, hover,
  formatting, rename, alias highlight. `LspCommandsSupport.executeCommand` is overridden so that
  `dynotable.*` commands go through `sendRequest` and their refusals are reported once.
- **Server-to-client requests.** If spike point 8 confirms it, `createLsp4jClient` wraps the
  handler and refuses `workspace/applyEdit`, `window/showDocument`, `client/registerCapability`
  and `workspace/configuration`, as VS Code does. The wrapper also escapes the text of
  `window/showMessage` and `window/showMessageRequest` before the platform shows it (the platform
  notification renders HTML; the Validate sentence arrives this way).
- **Lens row (Code Vision).** Run and Open come from the server lenses; `codeLensClicked` runs them
  through the same paths as the actions. Platform inlay hints are off. Our own Code Vision provider
  requests `textDocument/inlayHint` and `textDocument/codeLens` itself and shows each plan on its
  statement's lens row; it refreshes when a session starts and on each document change.
- **File types.** DynoTable SQL (`*.dynotable.sql`), PartiQL (`*.partiql`), OpenSearch
  (`*.opensearch`), one plugin icon. A `fileTypeOverrider` maps a `.sql` file to DynoTable SQL when
  it matches a `sqlFiles` glob of the open project whose content holds it (globs are relative to
  that project's base dir). A `sqlFiles` change calls `makeFileTypesChange` and restarts.
  Highlighting: TextMate bundle.
- **Shortcuts.** Run Cmd/Ctrl+Enter and Open Cmd/Ctrl+Shift+Enter, rebindable in Keymap. An
  `ActionPromoter` puts our actions first in DynoTable files only.
- **UI.** Results tool window (bottom), explorer tool window (left), status bar widget, actions under
  Tools › DynoTable, settings page Settings › Tools › DynoTable. Every label, cell, header, popup
  row and notification shows server text as plain text, never as Swing HTML.
- **Residual risk the native API keeps** (README › Privacy, decided): the platform
  prints the token in the Services LSP console on every connect, and in LSP debug logs.

## Technical Details

- **Language ids** (`getLanguageId`): DynoTable SQL → `sql`, PartiQL → `partiql`, OpenSearch →
  `opensearch`.
- **Client capabilities the server reads:** `window.showMessage`, `window.workDoneProgress`,
  `textDocument.completion.completionItem.snippetSupport`. The platform default holds them; a test
  checks the `initialize` the fake receives.
- **Custom requests** (`DynoTableServer : Lsp4jServer`, lsp4j `@JsonRequest`, return type
  `JsonElement`): `dynotable/listProfiles`, `dynotable/listTables {fresh?}`,
  `dynotable/listIndices {fresh?}`.
- **Commands** via `workspace/executeCommand` sent with `sendRequest`: `dynotable.runStatement`
  (→ `RunResult`), `dynotable.openInApp` (→ `null`), `dynotable.validateQuery` (→ sentence; the
  server also sends `showMessage`, so the plugin shows nothing more). "Client not running" is told
  apart from the `null` answer of `openInApp` by a flag set inside the sender lambda; it maps to
  the status reason. `CommandArgs.uri` is the URI of the platform's `didOpen` for that file, not
  `VirtualFile.url`.
- **Errors:** `ResponseErrorException.responseError`. `-32803` → action by `data.code` (`data`
  missing or not an object, or unknown code → as `run-failed`); text = the server's `message`.
  `-32601` on a command → the server's message, no action (only the connect-time listing latches
  `missing-request`). A future failed because the connection closed or its descriptor was retired
  → silent (the status already shows it).
- **Parsing:** `RunResult` arrives as a decoded `Object` tree (numbers are `Double`). The plugin
  converts it to `JsonElement` and validates it: a missing required field or a wrong primitive type
  (`totalRows` missing, `returnedRows: "1"`) fails; an extra key or an unknown enum value (`kind`,
  `reason`, `data.code`) is accepted. Strings inside lists and maps serialize as `JSON.stringify`
  does (U+2028 and U+2029 stay as they are; Gson's `JsonWriter` escapes them, so the serializer is
  our own). Numbers render as JavaScript `String(number)` does, at every
  depth (top-level cells, inside lists and maps, Copy JSON, the summary counts): `1` → `1`,
  `1730000000000` → `1730000000000`, `1e20` → `100000000000000000000`, `1e21` → `1e+21`,
  `0.000001` → `0.000001`, `1e-7` → `1e-7`, `-0.0` → `0` (the server sends a number that a double
  cannot hold as `{N}`). One serializer, with HTML escaping off, writes cells, CSV and Copy JSON.
- **RunResult cells:** a string shows as is; every other value (numbers, booleans, `null`, tagged
  `{N}` `{B}` `{SS}` `{NS}` `{BS}`, lists, maps) shows as compact JSON; a missing key is empty.
  `totalRows: null` → no total. CSV prefixes `'` to a cell that starts with `= + - @ \t \r \n`,
  except a number literal. The table's own copy (Cmd/Ctrl+C) uses the same guarded CSV.
- **Stale check:** `ProcessHandle.of(pid)` alive. On macOS and Linux also `info().user()` equal to
  `ProcessHandle.current().info().user()` (not `user.name`); empty or different → stale (the
  `EPERM` case). On Windows, alive only. No `pid`
  → skip. `ECONNREFUSED` → stale.
- **Port file:** a truncated file or a `port` that is not a number is not a port file yet (no
  latch); the next watcher event reads it again. Read with `java.nio`, never VFS. Poll while the
  directory does not exist. Ignore an event for the in-flight `(port, token)` and a delete. A
  dead-pid file's key after a failed launch does not relaunch.
- **Launch:** `ProcessBuilder` with `Redirect.DISCARD`, cwd = home, env from `launchEnv` (allow-list
  on macOS/Linux; Windows deny-list ignoring case), not `OSProcessHandler`. Detached from the IDE's
  session and process group (form from spike point 9). Windows spawns the newest `app-*` folder,
  not the Squirrel stub. Version from `Info.plist` / `app-*` before the spawn. Open DynoTable
  (windowed): `open -a <bundle>` on macOS, the binary elsewhere, no `--headless`, filtered env;
  refused when `userDataDir` is set.
- **Profile:** stored per project in `workspace.xml`; a choice from a replaced session is not
  stored; a change restarts (after a running query ends: `waiting-for-run`).
- **Settings:** `sqlFiles` (project, shared under `.idea`, default empty), `appPath` and
  `userDataDir` (IDE level, never project-shared). A changed `userDataDir` applies at once; a
  changed `appPath` applies at Reconnect.
- **New Query** a scratch file `query.dynotable.sql`, `query.partiql` or
  `query.opensearch` (the OpenSearch one starts with `// dynotable: index=… [target=…]`), through
  `ConnectCore` (never the platform start directly).
- **Plain text in Swing:** server strings (cells, column names, table and index names, profile
  names, refusal messages) are escaped or set on components that do not parse HTML.

## What Goes Where

- **Implementation Steps**: code, tests and docs in `jetbrains-dynotable` (local repo
  `/Users/dmitriy/Work/jetbrains-dynotable`).
- **Post-Completion**: steps outside the repo or outward-facing. Each needs the user's approval.

## Implementation Steps

### Task 1: Spike the unverified points (throwaway)

**Files:**
- Create: `/private/tmp/dt-jb-spike/` (throwaway Gradle project, deleted at the end)

- [x] minimal plugin on the minimum version, with own file types `*.dynotable.sql` and `*.partiql`; the app started through a wrapper as in `live-app.ts` with a prepared user data dir (integration on, signed in), never the real one
- [x] point 1 attach: `Socket(port, startProcess = false)` with a lazily read port; `initialize` with the token succeeds; completion works in `runIde`; the platform and the preflight reach the same loopback address (also with `-Djava.net.preferIPv6Addresses=true`)
- [x] point 2 lifecycle: a bad token and a refused port are visible only through the preflight; `serverStopped(false)` after the app quits; `serverStopped(true)` from a Services-view Restart, from Stop, from project close and from plugin unload, and how Restart is told apart (same descriptor back in `getClients`); `stopClients` then `ensureClientStarted` with a new descriptor starts a new session; two fast restarts leave exactly one session on the newest descriptor; whether `ensureStarted` waits while a modal dialog is open; how the plugin observes that a file's `didOpen` was sent (public API, or a fallback such as one lens retry after `serverInitialized`)
- [x] point 3 content check: a scratch file and a `.partiql` file outside the project get `didOpen`, also after a plugin-driven restart
- [x] point 4 lens row: platform inlay hints off; own Code Vision entry shows on the same row as the server Run lens and refreshes when a session starts; `codeLensClicked` override is called
- [x] point 5 highlighting: a TextMate `PluginBundle` (directory with a manifest, extracted from the plugin jar) colours the own PartiQL file type and a `.sql` file switched by `fileTypeOverrider`; check whether the bundled TextMate `sql` bundle colours DynoTable SQL (then no vendored SQL grammar)
- [x] point 6 shortcuts, in IDEA and in DataGrip: with an `ActionPromoter`, Cmd+Enter and Cmd+Shift+Enter in a DynoTable file run our actions; in a plain file, Split Line, Complete Current Statement and DataGrip Execute still work; `*.dynotable.sql` wins over the Database plugin's `.sql`
- [x] point 7 tests: a headless platform test connects to a TCP stub and gets diagnostics (`awaitDiagnosticsFromLspServer`)
- [x] point 8 client internals: `sendRequest` throws `ResponseErrorException` on an error answer; a pending future after a socket close and after a normal stop (does it complete?); the `executeCommand` answer type and number decoding; the default `initialize` capabilities; a wrapped handler in `createLsp4jClient` refuses `applyEdit`, `showDocument`, `registerCapability`, `configuration`
- [x] point 9 launch: the `--headless` app outlives the IDE, the IDE's terminal close (SIGHUP) and Ctrl+C on `runIde`; record the detach form that works per OS; on Linux, whether a snap-installed IDE rewrites the environment (port the vscode snap restore only if it does)
- [x] record each result in this plan under Task 1; ⚠️ adjust later tasks for each failure (point 7 → Starter/Driver under Xvfb; point 3 → stop and ask the user (the 2026.3 decision rests on it); point 8 handler wrap fails → the refusals and the `showMessage` escaping become residual risks)
- [x] delete `/private/tmp/dt-jb-spike/`

#### Task 1 results (2026-11-09, IU 2026.3 = 263.7307.200, JBR 25, Gradle 9.9.0, IntelliJ Platform Gradle Plugin 2.20.0)

Method: headless platform tests in the spike against a Node TCP stub that listens on 127.0.0.1 only and
answers from `contract/`. Sources read at tag `idea/263.7307.200`. No real app was started yet: the
prepared live user data dir does not exist on this machine (see ⚠️ below).

- **Build facts.** Tests need JUnit **6** (`junit-jupiter:6.0.x`); JUnit 5.13 fails with `NoSuchMethodError`.
  Test frameworks: `TestFrameworkType.Platform`, `JUnit5`, `JUnit5CodeInsight`, `Plugin.LSP` (the last
  gives `com.intellij.platform.lsp.testFramework`). JUnit 5 fixtures: `projectFixture`, `moduleFixture`,
  `tempPathFixture`, `codeInsightFixture`. Toolchain 21 compiles; the IDE runs on JBR 25.
- **Point 7 ✅** A headless test connects to a TCP stub, gets `didOpen`, diagnostics and completion.
  `awaitDiagnosticsFromLspServer` waits only for diagnostics that come after the call, so it races with a
  fast server: poll the highlighting instead. Tests that leave a client running fail teardown with
  "Thread leaked: LSP Listener": stop the clients and wait in `@AfterEach`.
- **Point 1 (partial) ✅/⚠️** `Socket(port, startProcess = false)` attaches; `initialize` carries the token;
  completion works (headless, not `runIde`). Default capabilities hold `window.showMessage`,
  `window.workDoneProgress` and `snippetSupport` (also `workspace.applyEdit`, `window.showDocument`,
  `workspace.configuration`). `didOpen` URI equals `VirtualFile.url` for a local file, `languageId` from
  `getLanguageId`. ❌ With `-Djava.net.preferIPv6Addresses=true` the platform connects to `::1` and gets
  `ECONNREFUSED` from a server on 127.0.0.1; the channel takes no host. Real app and `runIde` not checked yet.
- **Point 2 lifecycle.**
  - ✅ Bad token and refused port: `serverStopped(false)`, the client stays `ShutdownUnexpectedly`, no platform
    notification. Only the preflight can tell why.
  - ✅ App quit (socket closed): `serverStopped(false)`, client stays `ShutdownUnexpectedly`.
  - ✅ Explicit `stopClients`: `serverStopped(true)`; the client leaves `getClients` at once (synchronously), so
    `stopClients` then `ensureClientStarted` with a new descriptor is not dropped by the same-server-id check.
  - ✅ Two fast restarts: one live session, on the newest descriptor.
  - ❌ Platform Restart (`restartClient`, the Services view and widget action): `serverStopped(true)` comes
    **before** the same descriptor is back in `getClients` (`sameDescriptorInClients=false` at that moment);
    then `serverInitialized` comes again from the **same** descriptor. Platform Stop: `serverStopped(true)`,
    no client back. So Restart and Stop look the same inside `serverStopped`.
  - ✅ `ensureClientStarted` does not start while a modal dialog is open; it starts after the dialog closes.
    The post-init `didOpen` pass also runs only in a non-modal state.
  - ❌ `didOpen` observation: no public API. `LspClientManagerListener.fileOpened`/`addListener` are
    `@ApiStatus.Internal`. `getDocumentVersion(doc)` stays `0` after `didOpen` (the platform sends `didOpen`
    with version 0), so it is no signal. Source: after `serverInitialized` the start thread calls
    `openForOpenedOrUnsavedFiles()`, which sends `didOpen` for every open **or unsaved** supported file.
  - Project close and plugin unload: from source both stop explicitly (`serverStopped(true)`); runtime check
    moves to the Task 9 tests.
- **Point 3 ✅ (with one gap)** A scratch file (`isInContent = false`) and a `.partiql` file outside the project
  get `didOpen`, also after a plugin-driven restart. The 2026.3 decision holds for document sync.
  ❌ Quick fixes: the platform hides LSP quick fixes in files outside project content
  (`LspIntentionAction` logs "File is not within the project content"); a project file shows and applies the
  same fix. ✅ Workaround: `codeActionsCustomizer.createQuickFix` returns our `LspIntentionAction` subclass
  that overrides `isAvailable`/`invoke`/`generatePreview` and applies the action's `TextEdit`s to the
  current document only (after `FileModificationService.prepareVirtualFilesForWrite`). It shows and applies
  in both out-of-content files.
- **Point 4 ✅ (headless)** `LspInlayHintDisabled`: the platform sends no `textDocument/inlayHint` and the
  editor has no inlays. Our `CodeVisionProvider` (anchor `Top`, ordered after `LspCodeVisionProvider`) gives
  entries on the same lines as the Run/Open lenses, and the host runs it by itself. A lens click calls our
  `codeLensClicked`. The visual row and the refresh at session start need the `runIde` check.
- **Point 5 ✅** Bundle = directory with a VS Code style `package.json` (languages + grammars), copied from
  the jar to `PathManager.getSystemPath()/…`. Own file types need **both** `lang.syntaxHighlighterFactory`
  (`TextMateSyntaxHighlighterFactory`) and `editorHighlighterProvider` (`TextMateEditorHighlighterProvider`);
  without the second, the editor throws "Token type … is not registered". TextMate picks the grammar by
  **file name**: `.partiql` → our grammar; `*.dynotable.sql` → the bundled `source.sql` grammar. So no
  vendored SQL grammar. A `.sql` file switched by the overrider has the same name rule (inferred; Task 5
  tests it). With the Database plugin loaded, `*.dynotable.sql` gets our file type.
- **Point 6 ✅ (IDEA with the bundled Database plugin; DataGrip itself not run)** With the `ActionPromoter`,
  Cmd/Ctrl+Enter and Cmd/Ctrl+Shift+Enter put our actions first in DynoTable files (default and macOS
  keymaps). In `plain.sql` and `plain.txt` Split Line and Complete Current Statement stay first;
  `Console.Jdbc.Execute` is bound and untouched by our promoter (it returns nothing outside our files).
- **Point 8 ✅** Error answer → `sendRequest` throws `ResponseErrorException` (`responseError.data` =
  `{"code":"profile-required"}`). No timeout: a held request is still pending after 12 s. `executeCommand`
  answers `LinkedTreeMap` with `Double` numbers (`3.0`). `sendRequest` on a stopped client returns `null`.
  ❗ A pending `sendRequest` **never completes** after a socket close or a normal stop (checked 5 s and 15 s),
  so the plugin must fail its in-flight run itself on every stop. ✅ A wrapped `LspServerNotificationsHandler`
  in `createLsp4jClient` refuses `workspace/applyEdit`, `window/showDocument`, `client/registerCapability`,
  `workspace/configuration` (the server gets our `-32601`). `window/showMessage` reaches the platform
  notification with its HTML unescaped, so the escaping in the wrapper is needed.
  `serverInitialized` runs on the "LSP Listener" thread.
- **Point 9 (fake app) ✅/⚠️** A plain `ProcessBuilder` child dies on terminal Ctrl+C (SIGINT to the group)
  and terminal close (SIGHUP); it lives on IDE SIGTERM/SIGKILL. A `setsid` wrapper
  (`/usr/bin/perl -MPOSIX -e 'POSIX::setsid(); exec @ARGV' <app> --headless`, exit code passes through)
  survives all four. A `/bin/sh -c "trap '' HUP INT; exec …"` wrapper survives only while the app keeps the
  ignored signals. Real app, Windows and Linux snap not checked.
- **`runIde` checks (2026-11-09)** in IntelliJ IDEA 2026.3 and DataGrip 2026.3 (sandbox; DataGrip on a
  non-commercial licence the user chose), against the TCP stub:
  - Point 4 ✅ the plan entry shows on the **same row** as Run and Open in DynoTable, after them.
    ❌ It does **not** appear when the session starts (the platform refreshes only its own lens provider);
    it appears after an edit or a tab switch. So the plan provider must invalidate itself on
    `serverInitialized` (Task 13).
  - Point 6 ✅ IDEA and DataGrip: Cmd+Enter and Cmd+Shift+Enter in a DynoTable file run our actions (text
    unchanged); in `plain.sql` IDEA runs Split Line / Complete Current Statement, DataGrip runs its own
    Execute ("Nothing to run", no data source) / Complete Current Statement.
  - Point 5 ✅ (headless) a `.sql` file switched by a `fileTypeOverrider` gets DynoTable SQL with the
    TextMate `source.sql` colours; another `.sql` file keeps the Database plugin's SQL highlighter.
    TextMate colours also show in both IDEs.
- ⚠️ **Incident (2026-11-09, live setup).** The installed 1.19.0 release ran through a wrapper with
  `--user-data-dir=/private/tmp/dt-jb-live/userData --use-mock-keychain` and wrote its app data there.
  Its auto-updater then downloaded 1.19.2 and relaunched the app **without the wrapper flags** (new pid,
  parent 1), so the app ran on the real data dir. A delegated UI agent then turned Editor integration on
  and added two profiles in the real app. All of it was reverted in the UI (integration off again, the
  two profiles deleted, the real `lsp-port.json` gone); the installed app stays updated to 1.19.2.
  Rule for every live setup: use a build that cannot update and relaunch itself (a dev build), and before
  any test prove that the real data dir's mtime does not change and that the port file appears only in
  the test dir.
- **Live checks (2026-11-09)** against an isolated **dev build** (1.19.1, `--headless`, no updater):
  wrapper `/private/tmp/dt-jb-live/dynotable-dev` sets the app's user data directory override and `--user-data-dir` to
  `/private/tmp/dt-jb-live/devData` and execs the dev build. Proven before the tests: `lsof` shows 0
  open files under the real data dir (68 under the test dir); the port file appears only in the test dir;
  the server listens on `127.0.0.1:41120` only. Side effect accepted by the user: a dev launch registers
  the dev build as the `dynotable://` handler.
  - Point 1 ✅ attach, `initialize` with the token, `serverInfo.version` = 1.19.1, keyword completion
    (unbound session). The IPv4-only listen confirms the `preferIPv6Addresses` limit above.
  - Point 2 ✅ bad token → `serverStopped(false)`; app SIGTERM with a live session → `serverStopped(false)`
    and the app deletes its port file. Preflight over our own socket: `initialize` (token, no profile),
    `shutdown`, `exit`; the app keeps serving.
  - `didOpen` signal ✅ the app sends `publishDiagnostics` for every opened document, also a clean one
    (`n=0`). A command for a document the server never saw is refused: `-32803`, `data.code: run-failed`,
    "No open DynoTable document at <uri>. Open the file before running it."
  - Point 9 ✅ (macOS) plain `ProcessBuilder`: the app dies on terminal Ctrl+C and close; on IDE kill it
    starts to quit (port file gone). `setsid` wrapper: the app survives all three and keeps serving.
    Windows and Linux snap not checked (no machine).

### Task 2: Scaffold the repository

**Files:**
- Create: `/Users/dmitriy/Work/jetbrains-dynotable/` (`settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `src/main/resources/META-INF/plugin.xml`, `LICENSE` MIT, `.gitignore`, `CLAUDE.md`, `CONTRIBUTING.md`, `README.md` stub)
- Already done: this plan is at `docs/plans/20261108-jetbrains-plugin.md` in this folder; commit it with the scaffold

- [x] `git init` locally, default branch `main`; no GitHub repo yet (see Post-Completion)
- [x] IntelliJ Platform Gradle Plugin 2.x, Kotlin; `sinceBuild` = the minimum version; `untilBuild` explicitly open; depends on `com.intellij.modules.lsp` and `org.jetbrains.plugins.textmate`; `plugin.xml` name, vendor, description and change notes
- [x] JUnit 5 API for unit tests; platform test framework with the LSP test helpers for integration tests; a `testLive` task that runs only `@Tag("live")`. Spike: the runtime needs `junit-jupiter` **6.x** (5.13 fails); `testFramework(Platform, JUnit5, JUnit5CodeInsight, Plugin.LSP)`; IntelliJ Platform Gradle Plugin 2.20.0, Gradle 9.9.0, Kotlin 2.4.21, toolchain 21; type `DB` builds against DataGrip
- [x] `CLAUDE.md` and `CONTRIBUTING.md` with the repository rules from Development Approach
- [x] write a manifest test: `plugin.xml` depends on `com.intellij.modules.lsp`; `sinceBuild` equals the minimum version that the README names (engines floor); no action id equals a server command id (`dynotable.runStatement`, `dynotable.openInApp`, `dynotable.validateQuery`). Done: depends (lsp, textmate), `since-build` = README minimum, `until-build` open. Not written: the action-id check. In VS Code a contributed server command id clashes with the language client's own registration; JetBrains action ids and LSP command ids are separate namespaces, so no bug for it to catch (Rule 8)
- [x] `./gradlew build test` passes

### Task 3: Copy the protocol, fixtures and grammars, with a CI drift check

**Files:**
- Create: `PROTOCOL.md`, `contract/*`, `src/main/resources/textmate/dynotable/` (bundle manifest + grammars), `scripts/drift-check.sh`

- [x] copy `PROTOCOL.md`, `contract/` and `syntaxes/*.tmLanguage.json` from vscode-dynotable `main`; wrap the grammars in a TextMate bundle: a directory with a VS Code style `package.json` (languages `partiql` → `.partiql`, `opensearch` → `.opensearch`, and their grammars). No SQL grammar: spike point 5 showed the bundled TextMate `source.sql` colours DynoTable SQL
- [x] `scripts/drift-check.sh`: list `contract/` from the upstream tree, fetch `PROTOCOL.md`, `contract/*` and the two grammars, map the grammar paths, fail with the differing, missing or extra files; it runs as its own CI job, not in the local `gate`. Done: the script shallow-clones upstream (`DRIFT_UPSTREAM`, `DRIFT_REF` override it) and passes against `main` today; the CI job itself is wired in Task 18
- [x] test the script on a changed local copy (must fail), an extra upstream fixture (must fail) and a clean copy (must pass). Done: `DriftCheckTest` (JUnit, local upstream repo, no network; skipped on Windows)

### Task 4: Protocol types and fixture parsing

**Files:**
- Create: `src/main/kotlin/com/dynotable/protocol/Protocol.kt`, `src/test/kotlin/.../ProtocolTest.kt`, `ContractTest.kt`

- [x] Kotlin types for `ProfileRow`, `ListTablesResult`, `ListIndicesResult`, `CommandArgs`, `RunResult`, port file; conversion from a decoded `Object` tree or `JsonElement`; lsp4j types for `initializeResult`, `codeLensList`, `progressList`, `showMessage`, `responseError`. Done: `protocol/Protocol.kt`; the five lsp4j shapes use the lsp4j classes as decoded by the platform's `MessageJsonHandler` Gson (no own type)
- [x] validation as in Technical Details › Parsing; JavaScript number formatting
- [x] write a contract test: every `contract/` fixture parses by its manifest shape, with a strict check per fixture (a fixture key with no Kotlin field fails, so a misnamed optional field such as `itemsScanned` is seen). Done: own types parse with `strict = true`; lsp4j types must round-trip through the lsp4j Gson unchanged; lens arguments and `responseError.data` also parse strictly. Checked by mutation: a renamed `itemsScanned`, an extra `initialize` capability and a misspelt `data.code` each fail. Not ported: `contract.test.ts` "lists every fixture file in the manifest": the drift check already fails on a fixture that upstream does not have
- [x] write protocol tests: missing `totalRows` fails; `returnedRows: "1"` fails; an extra key and `kind: "serverless"`, a new `reason`, a new `data.code` are accepted; a `Double` `1.0` from the decoded tree formats as `1`; `1e21` formats as `1e+21`. Done: also strict refuses each of the lenient cases (else the contract test sees no drift); the number test has one row per branch of the JavaScript rule
- [x] run tests

### Task 5: File types, TextMate highlighting and settings

**Files:**
- Create: `files/FileTypes.kt`, `files/SqlFilesOverrider.kt`, `files/SqlGlobs.kt` (pure), `files/TextMateBundle.kt`, `settings/DynoTableSettings.kt`, `settings/DynoTableConfigurable.kt`
- Modify: `plugin.xml`

- [x] three file types with one plugin icon; TextMate bundle provider that copies the bundle from the jar to a directory under `PathManager.getSystemPath()`; for each own file type register **both** `lang.syntaxHighlighterFactory` (`TextMateSyntaxHighlighterFactory`) and `editorHighlighterProvider` (`TextMateEditorHighlighterProvider`) (spike point 5: without the second the editor throws). Done: file types "DynoTable SQL" (`*.dynotable.sql`), "DynoTable PartiQL", "DynoTable OpenSearch" with prefixed language ids; the OpenSearch grammar's `source.json.comments` include resolves to the IDE's bundled JSON bundle (checked headless)
- [x] `fileTypeOverrider`: a `.sql` file is DynoTable SQL when it matches a `sqlFiles` glob of the open project whose content holds it (globs relative to that project's base dir). Done: own matcher with the VS Code glob rules (`SqlGlobs`); the `sqlFiles` setter calls `makeFileTypesChange` (Task 9 adds the restart there)
- [x] settings and settings page; `sqlFiles` project-level (shared, default empty); `appPath`, `userDataDir` application-level. Done: `.idea/dynotable.xml` for the project; app level with roaming off (as VS Code `machine` scope)
- [x] write unit tests for the glob match: a root-level file under `**/`; a file outside the project. Done: also a sibling folder with the same path prefix, a single star in one segment, a brace group
- [x] write tests: `appPath` and `userDataDir` are application-level (never in project-shared storage); the bundle path resolves to a directory with the grammars in the built plugin sandbox. Done: `SettingsScopeTest`; the bundle path is checked through its effect: `HighlightingTest` opens a `.partiql`, `.opensearch` and `.dynotable.sql` file in the test sandbox and finds the scoped TextMate tokens of each grammar (a missing bundle, grammar or JSON include gives unscoped tokens), and a `sqlFiles` change switches an open `.sql` file at once
- [x] run tests

### Task 6: Discovery and app locator (pure rules)

**Files:**
- Create: `src/main/kotlin/com/dynotable/connect/Discovery.kt`, `AppLocator.kt`, `LaunchEnv.kt`, tests

- [x] port-file path per OS (macOS, Windows `%APPDATA%`, Linux `XDG_CONFIG_HOME`), replaced by `userDataDir`, and in test mode by the test user-data dir
- [x] port-file parse and stale check (Technical Details). Done: `Fs.Local` reads with `java.nio`; a missing, truncated or malformed file is `null`
- [x] app locator per OS, replaced by `appPath` and, in test mode, by the test app root: macOS `.app` or inner binary → `Info.plist` version; Windows newest `app-*` folder (Squirrel), half-installed folder skipped, beta name; Linux path
- [x] `meetsMinimum` (pre-release and non-semver versions); `launchEnv` (allow-list on macOS/Linux, deny-list on Windows ignoring case; no snap branch). Done: versions compare with the platform's `com.intellij.util.text.SemVer` (no own semver)
- [x] minimum-version override for tests and live tests (a test-mode-only property, as `DYNOTABLE_MIN_APP_VERSION`); ignored outside test mode. Done: `Overrides.of(testMode, property)` with the system properties `dynotable.test.userDataDir`, `dynotable.test.appRoot`, `dynotable.test.minAppVersion`; Task 9 passes `isUnitTestMode` as `testMode`
- [x] write tests from `discovery.test.ts` and the pure `connect-core.test.ts` cases: the min-version override is ignored outside test mode; other-user or empty-user `pid` on macOS/Linux → stale; Windows alive `pid` → not stale; no `pid` → connect; truncated file and string `port` → not a port file; `app-1.10.0` over `app-1.9.0`; half-installed folder; plist version; pre-release versions; allow-list drops `AWS_PROFILE` and `NODE_OPTIONS`; Windows deny-list ignores case; port-file path per OS; the test seams do nothing outside test mode. Done: `DiscoveryTest`, `AppLocatorTest`, `LaunchEnvTest`; also a real-`ProcessHandle` check that the own live process is not stale and an ended one is (skipped on Windows). Not ported: the snap restore (no snap branch, as planned) and the Development-mode row of `overrides` (no such mode here)
- [x] run tests

### Task 7: Launcher

**Files:**
- Create: `status/Reasons.kt` (port of `reasons.ts`, plus `untrusted` and `stopped`), `connect/Launcher.kt`, `connect/OpenApp.kt`, `src/test/kotlin/.../fake/FakeApp.kt` (fake binary that records argv, env and cwd, then exits with a given code or serves), tests

- [ ] reasons with their sentences and actions; write the `reasons.test.ts` checks (each reason has a sentence; the first action is not Reconnect)
- [ ] spawn per Technical Details › Launch; version check before the spawn (an old app would open its full window). Detach form (Decision 7): on macOS and Linux `/usr/bin/perl -MPOSIX -e 'POSIX::setsid(); exec @ARGV' <app> --headless`, plain spawn plus a log line when `/usr/bin/perl` is missing
- [ ] exit codes (`EXIT_RESULTS`): no exit → wait for a new `(port, token)`, 30 s → `timed-out`; `0` → wait ~20 s, spawn once more, then `not-serving`; `1` → `start-failed`; `3` → `integration-off`; other → `crashed`; spawn error → `spawn-failed`; no binary → `not-installed`; `userDataDir` set → `launcher-off`, never spawn
- [ ] Open DynoTable (windowed) per Technical Details
- [ ] test mode: spawn and open-external throw unless a fake binary is set
- [ ] write tests with the fake binary: each exit code → its reason; exit `0` → one more spawn, and the wait accepts only a new `(port, token)`, never the stale file's key; an app below the minimum version → `update-app` and no spawn; the start timeout (short in test); argv, filtered env and cwd as specified; Open DynoTable has no `--headless`, uses `open -a` on macOS, gives `not-installed` / `spawn-failed`, and is refused with `userDataDir` set
- [ ] run tests

### Task 8: Fake server and integration harness

**Files:**
- Create: `src/test/kotlin/.../fake/FakeServer.kt`, `src/test/kotlin/.../harness/Harness.kt`

- [ ] fake server on a `ServerSocket` with the lsp4j launcher; answers from `contract/`; replaceable handlers; records received messages; more behaviours from `fake-server.ts` only with the first test that needs them
- [ ] harness: test user-data dir with a port file for the fake; test app root and fake binary; capture of every notification (plugin and platform) with action clicks; scripted popup answers; the status wait is added in Task 9
- [ ] no standalone test here (a fake-only test would test lsp4j); the harness is exercised by the first Task 9 tests, including the `showMessage` positive control
- [ ] spike lessons: poll the highlighting instead of `awaitDiagnosticsFromLspServer` (it races with a fast server); stop the clients and wait in `@AfterEach` (else "Thread leaked: LSP Listener"); `withContext(Dispatchers.EDT + ModalityState.any().asContextElement())` to leave a modal state; the platform's internal Restart/Stop are reached by reflection only in tests

### Task 9: Connect core, preflight and status widget

**Files:**
- Create: `connect/ConnectMachine.kt` (pure), `connect/ConnectCore.kt` (project service), `connect/Preflight.kt`, `connect/DynoTableIntegrationProvider.kt`, `connect/DynoTableClientDescriptor.kt`, `connect/DynoTableServer.kt`, `connect/PortFileWatcher.kt`, `ui/StatusWidget.kt`, `actions/ReconnectAction.kt`, `actions/OpenDocsAction.kt`, `actions/SetAppPathAction.kt`, `actions/DownloadAction.kt`
- Modify: `plugin.xml`

- [ ] statuses (port of `status.ts`) on top of the Task 7 reasons
- [ ] `ConnectMachine` per Solution Overview › Connect core (preflight, start with a new descriptor per attempt, in-flight rule with its deadlines, queued restarts, retire-then-stop, only the current descriptor counts, latch with `stopClients` on a background job, stop before/after initialized, recheck)
- [ ] spike changes: platform Restart vs Stop per Decision 3 (`disconnected(stopped)` at once, `connected` again on a later `serverInitialized` from the same, not retired descriptor); "file is open on the server" per Decision 6 (the wrapped handler records `publishDiagnostics` URIs per session; local check, then wait up to 3 s, then send); a pending `sendRequest` never completes after a stop, so every stop of the current descriptor fails the in-flight run itself; `serverInitialized` runs on the "LSP Listener" thread; `stopClients` removes the client from `getClients` at once; `ensureClientStarted` waits while a modal dialog is open
- [ ] glue: integration provider (`fileOpened` only schedules), descriptor (preflight `(port, token)`, lazy profile, `getLanguageId`, `DynoTableServer`), `lspServerListener`, port-file watcher, triggers (restored editors, commands wait for the connect, Reconnect), untrusted project → `untrusted`; restarts never use `startClientsIfNeeded` or `stopAndRestartClientsIfNeeded`; a `sqlFiles` change calls `makeFileTypesChange` and restarts
- [ ] reason actions built here: Reconnect (clears the latch and restarts), Open Documentation (`https://dynotable.com/docs/editors`), Set App Path (opens the Task 5 settings page at `appPath`), Download DynoTable; Open DynoTable from Task 7; Select Profile comes in Task 14 (until then the reason offers it as a label only)
- [ ] status bar widget: profile or reason; click shows the reason's actions; a latched reason shows once per command, not once per file open; a refused start shows only our sentence
- [ ] write unit tests for `ConnectMachine` from `connect-core.test.ts` (fake clock): restart during a start; shared restart requests; a restart storm gives one session on the newest descriptor; a failed restart does not block the next; a latch from an older attempt is ignored; a late `serverStopped` from a retired descriptor neither latches nor fails a run; retiring a descriptor fails its in-flight run at once; a `-32601` latch is not replaced by `connection-ended` when its own stop arrives; a `serverInitialized` from an old descriptor during a current attempt stops nothing; an unrequested normal stop after `serverInitialized` → `disconnected(stopped)`, and a later `serverInitialized` from the same descriptor → `connected` (platform Restart), with none the next trigger connects (platform Stop), while closing → nothing, an unexpected one → `connection-ended`; a plugin restart waits for a running query, a latch fails it at once; an attempt that never shows in `getClients` → `start-failed` (clock starts after `stopClients` returns), one that never reports in 40 s → `timed-out`, both stop the client; a dead or other-user `pid` → launch, and no socket is opened to the stale port; the user-data dir does not exist until the launched app creates it → connect; concurrent triggers → one spawn; a new `(port, token)` during an attempt reconnects once it latches; a dead-pid key after a failed launch does not relaunch; the 40 s `initialize` timeout; every preflight outcome maps to its reason; stop before initialized → `start-failed`
- [ ] write integration tests from `connect.test.ts`: a server `showMessage` of each type is captured (positive control); token sent, no `profile` in the preflight; bad token → reread once → `auth-failed`; a refused start gives exactly our one sentence and no platform notification, also after the server closes the socket 250 ms later; restart storm: slow `initialize`, two `sqlFiles` changes → exactly two platform sessions (the fake counts sessions that sent `initialized`; the preflight never sends it), one live session, and a `dynotable/listTables` request gets an answer; a Services-view Restart while connected ends `connected` again on the same descriptor and shows no `connection-ended`; a Services-view Stop gives `disconnected(stopped)` and does not reconnect until the next trigger; closing the project opens no preflight and starts no client; trusting an untrusted project connects; Trust Project opens the platform trust dialog, and cancelling it keeps the project untrusted; `signed-out` on the connect-time `listTables` leaves the status `connected`; rotated token → exactly one live session (the preflight socket closed); preflight passes but the platform `initialize` is refused → status latches; signed-out latches; version gate; `-32601` latch; unbound; app quit → next file open spawns nothing and no "Terminated" client stays; platform Restart while latched connects nothing; new port file reconnects; watcher echo and delete ignored; capabilities in `initialize`; `fileOpened` returns at once with a slow `initialize`; the plugin's own logs and `workspace.xml` never hold the token, checked after a bad token, a rotated token, a malformed port file and a failed preflight (positive control: a log line that holds it is found)
- [ ] write integration tests from `no-app.test.ts`, `restored.test.ts`, `no-folder.test.ts`, `workspace-folders.test.ts`: not installed → Download from the status widget; Open DynoTable from a reason starts no `--headless`; changed `userDataDir` applies at once; changed `appPath` applies at Reconnect; restored editor connects; `didOpen` carries `sql`, `partiql` and `opensearch` for the three file types; a plain `.sql` file and a non-DynoTable file start no connection; a `.partiql` file outside the project is served; with two open projects, project A's `sqlFiles` does not switch a `.sql` file of project B; a `sqlFiles` change makes an open `.sql` file served; untrusted project spawns nothing, opens no preflight and shows `untrusted`
- [ ] run tests

### Task 10: Refusals, command errors and server requests

**Files:**
- Create: `refusals/Refusals.kt` (pure), `refusals/Notifications.kt`, `editor/DynoTableCommands.kt` (`LspCommandsSupport` override, the first lazy-connect command), `connect/ServerHandler.kt` (wrapped `LspServerNotificationsHandler`, if spike point 8 allows), tests

- [ ] refusal → action (port of `refusals.ts`): `profile-required` → Select Profile; `profile-window-required`, `reauth-required`, `signed-out` → Open DynoTable; `stage-in-app` (Run only) → Stage in DynoTable; unknown code or bad `data` → as `run-failed`; text = server `message` as plain text; client cancel → silent; closed connection → silent; client not running → the status reason
- [ ] `LspCommandsSupport.executeCommand`: `dynotable.*` commands go through `sendRequest` and the refusal path; other commands keep the default
- [ ] wrapped `LspServerNotificationsHandler` refuses `workspace/applyEdit`, `window/showDocument`, `client/registerCapability`, `workspace/configuration`, and escapes the text of `window/showMessage` and `window/showMessageRequest` (or, when spike point 8 failed, a written reason in the regression map and both in the residual risks)
- [ ] `dynotable.*` commands go through the lazy connect (wait for the connect; the reason once when latched)
- [ ] own quick fix (Decision 4): `codeActionsCustomizer.createQuickFix` returns an `LspIntentionAction` subclass that overrides `isAvailable`, `invoke` and `generatePreview` and applies the action's `TextEdit`s to the current document only, after `FileModificationService.prepareVirtualFilesForWrite`; write an integration test: in a scratch file the fix shows and applies (the platform's own fix hides there)
- [ ] write unit tests from `refusals.test.ts`: each code → its action (`profile-required`; `profile-window-required`, `reauth-required`, `signed-out` → Open DynoTable; `stage-in-app`); unknown code; `data` missing and not an object; Stage's own refusal does not offer Stage again; client cancel and closed connection → silent; client not running → status reason
- [ ] write integration tests: Validate against the index gives exactly one notification; a `profile-required` refusal on a quick-fix command shows its sentence with a Select Profile action; a refusal message and a server `showMessage` `<html><img src=…>` show as plain text; the four server requests are refused and the document stays unchanged; `-32601` on Validate shows the server message and keeps the session; from `no-app.test.ts`: Validate shows the latched reason exactly once, from idle and when already latched; Validate during a cold start waits and then answers (no "try again"); Validate in an untrusted project shows the `untrusted` sentence once and starts nothing; a command whose file the server never opens shows the "has not opened this file" sentence after the deadline and frees the next command
- [ ] run tests

### Task 11: Run action, shortcuts and results tool window

**Files:**
- Create: `run/RunAction.kt`, `run/RunTarget.kt` (pure), `run/DynoTableActionPromoter.kt`, `results/ResultsModel.kt` (pure), `results/ResultsToolWindow.kt`, tests
- Modify: `plugin.xml` (action, keymap Cmd+Enter / Ctrl+Enter, promoter, tool window)

- [ ] run target (port of `commands.ts`): selection (`{start, end}`), else the statement at the caret (server lens args; a caret at a statement's end runs it; a caret outside every statement sends no range), else the whole file; a failed lens request runs nothing; a document change while the lens request is pending runs nothing; OpenSearch: selection or whole file, no range when no selection
- [ ] run through `sendRequest`; one run at a time; Cancel cancels the future (`$/cancelRequest`) with the run id of the run the view shows, shows the `cancelled` view and ignores a late answer and a cancel for another run; removing the tool window content cancels; any stop of the client fails the in-flight run; progress only from the platform's `$/progress`
- [ ] results model (port of `results-model.ts`): `summary()`, cells, guarded CSV, JSON
- [ ] tool window: the executed statement (`executedStatement`, plain text, also for an empty result) above a `JBTable` (columns as returned, plain-text headers and cells), summary line, Copy CSV, Copy JSON, Cancel; the table's own Cmd/Ctrl+C uses the guarded CSV; an unreadable answer keeps the last view
- [ ] `ActionPromoter`: Run first in DynoTable files only
- [ ] write unit tests from `results-model.test.ts`, on trees decoded as lsp4j decodes them (`Double` numbers): `totalRows: null` shows no total; `effectiveRowCap` in the summary; exact total next to `truncated`; summary counts show `3 of 120`, not `3.0 of 120.0`; the number cases of Technical Details › Parsing as cells; `{"qty":1}` stays `{"qty":1}` in a cell and in Copy JSON; a nested string `a=b<c` stays as it is; a nested string with U+2028 stays as it is; an exact-decimal `{N}` stays tagged text in Copy JSON; an empty result shows "No rows."; a tagged Set inside a map shows as JSON; qualified column `o.orderId` is a literal key; column order kept; missing key empty; CSV formula guard with every bypass form (`= + - @ \t \r \n`, header cell, guard before quote, `-1+1`, `+1)`, `-2+cmd|'/C calc'!A0`, `-1.5abc`) and number literals intact
- [ ] write integration tests from `run.test.ts` and `media/results.test.ts`: Run at the caret sends the statement range; selection Run sends `{start, end}`; OpenSearch with no selection sends no range; failed lens request runs nothing; pending-edit race runs nothing; an integer cell from the fake shows `1`; Copy CSV and the table's Cmd/Ctrl+C both apply the formula guard; Copy JSON; a hostile `<html><img src=…>` column name, cell and executed statement show as text; Cancel sends `$/cancelRequest` and shows nothing; late answer ignored; Reconnect during a run waits for it (`waiting-for-run`); a latch during a run fails that run at once and frees the next Run; Cmd+Enter during a cold start runs once the file is open on the server; a second Run while one runs is refused; a platform Restart during a run fails the run and frees the next Run; a restart from a `sqlFiles` change waits for the run (`waiting-for-run`); an answer that is not a `RunResult` gives one error and keeps the last view; Run after `connection-ended` shows the reason and no empty view; Cmd+Enter in a DynoTable file runs and in a plain file splits the line
- [ ] run tests

### Task 12: Open in DynoTable and Stage

**Files:**
- Create: `run/OpenAction.kt`
- Modify: `plugin.xml` (keymap Cmd+Shift+Enter / Ctrl+Shift+Enter, promoter entry)

- [ ] send the server's Open lens args for the statement at the caret; no lens → info notification, no request; a document change while the lens request is pending → nothing; OpenSearch → not offered
- [ ] Stage in DynoTable (from a `stage-in-app` refusal): `openInApp` with the refused run's own args (its range, or `{uri}` only when it had none)
- [ ] `profile-window-required` shows once; Open after `connection-ended` shows the reason, never a success
- [ ] write integration tests from `run.test.ts`: caret Open sends the lens args; no lens → info and no request; pending-edit race → nothing; the session drops after the lens answer and before `executeCommand` → the status reason, not a silent success; Stage sends the refused run's range; `profile-window-required` shows once; Cmd+Shift+Enter in a DynoTable file opens and in a plain file completes the statement
- [ ] run tests

### Task 13: Lens row: plan Code Vision and lens clicks

**Files:**
- Create: `editor/PlanLenses.kt` (pure), `editor/PlanCodeVisionProvider.kt`, `editor/DynoTableCustomization.kt`, tests

- [ ] port the `plan-lens.ts` rule: a hint in a statement range (end inclusive) moves to that statement's lens anchor, else to the start of its own line; label parts join; trim
- [ ] `LspCustomization`: `LspInlayHintDisabled`; `codeLensClicked` sends `dynotable.runStatement` to the Task 11 run path and `dynotable.openInApp` to the Task 12 path; `commandsCustomizer` from Task 10
- [ ] Code Vision provider (anchor `Top`, ordered after `LspCodeVisionProvider`) requests `textDocument/inlayHint` and `textDocument/codeLens` itself for the same document version; on `serverInitialized` it invalidates itself through the Code Vision host (spike: the platform refresh does not recompute it); refreshes on a document change; a failed request shows no plan
- [ ] write unit tests for the placement rule (the three `plan-lens.test.ts` cases)
- [ ] write integration tests: the fake's plan shows on its statement's lens row after the session starts with the file already open; the editor's inlay model has no platform hints; a Run lens click runs with the argument's range
- [ ] run tests

### Task 14: Profiles

**Files:**
- Create: `profiles/Profiles.kt` (pure, port of `pickerOf`), `profiles/SelectProfileAction.kt`, tests

- [ ] popup from `listProfiles` (plain-text rows): rows with `bindable: false` disabled with their reason (unknown reason → general words); empty roster → sentence and Open DynoTable; no client auto-pick (the server binds a roster of one)
- [ ] the stored id is sent on the first connection; a choice from a replaced session is not stored; an incomplete pick → warning, no reconnect; a change restarts
- [ ] an unbound Run lens click opens the picker; the status bar shows the requested profile
- [ ] write unit tests for `pickerOf` (empty roster, incomplete row, non-bindable row with an unknown reason)
- [ ] write integration tests from `profiles.test.ts`: stored id sent on first connection; choice during a session change not stored; incomplete pick → no reconnect; unbound lens click opens the picker; empty roster offers Open DynoTable; status bar shows the requested profile
- [ ] run tests

### Task 15: Explorer tool window, New Query and Pick Index

**Files:**
- Create: `explorer/ExplorerModel.kt` (pure), `explorer/ExplorerToolWindow.kt`, `query/NewQuery.kt`, `query/Directive.kt` (pure), `query/PickIndexAction.kt`, tests

- [ ] tree (plain-text names): DynamoDB → tables; OpenSearch → targets → indices; rows for `unreachable`, `tableListingFailed`, `indexListingFailed`, `partial`; unbound → the reason sentence as the tree message
- [ ] showing the tool window is a connect trigger; tables show while the index listing is still in flight; an answer from a replaced session is dropped
- [ ] double-click or Enter opens a new query; context menu New Query; toolbar Refresh (`fresh: true` to both listings)
- [ ] New Query (Technical Details) with a dialect picker; directive rules from PROTOCOL.md (a name on two targets or an incomplete listing needs `target=`; a target name with a space cannot be pinned → warning, nothing opens)
- [ ] Pick Index writes line 0; nothing when the session changed; a warning when no index is listed
- [ ] write unit tests from `directive.test.ts` and `explorer-model.test.ts`
- [ ] write integration tests from `explorer.test.ts` and `editor-commands.test.ts`: showing the view connects; Refresh sends `fresh: true`; tables before indices; stale session dropped; unpinnable index warns; table click opens `SELECT *` in a scratch file whose path has a space and whose `didOpen` reaches the fake, and Run there sends `args.uri` equal to the `didOpen` URI; index click opens a directive file; New Query through the dialect picker; profile change refetches; Pick Index cases; unbound message
- [ ] run tests

### Task 16: Attach SQL File and the Tools menu

**Files:**
- Create: `actions/AttachSqlFileAction.kt`, `actions/Attach.kt` (pure)
- Modify: `plugin.xml` (Tools › DynoTable group with every action)

- [ ] Attach SQL File appends the project-relative path (`\` → `/`, leading and trailing spaces kept), escaped for the Task 5 glob matcher, to `sqlFiles`; a file outside the project or no project → message, no change; then the `sqlFiles` change path
- [ ] Tools › DynoTable lists Run, Open in DynoTable, New Query, Pick Index, Attach SQL File, Select Profile, Reconnect, Open Documentation, Refresh Explorer
- [ ] write unit tests from `attach.test.ts`, checked through the Task 5 matcher (the escaped pattern matches the file and not a near name such as `p.sql`): `[prod].sql`, `{a}.sql`, `a*?.sql`, a padded name, a Windows path, no project → refused
- [ ] write an integration test: Attach SQL File on `[prod].sql` makes that open file served
- [ ] run tests

### Task 17: Live tests against the real app

**Files:**
- Create: `src/test/kotlin/.../live/LiveTest.kt`, `live/LiveApp.kt` (wrapper per Testing Strategy › Live)

- [ ] `@Tag("live")` tests through the wrapper; never the real app data dir; the isolated dev-build wrapper and the isolation proof from Testing Strategy › Live (a release build can relaunch itself through its updater)
- [ ] cases from `live.test.ts`: connect and `serverInfo.version`; the server answers `textDocument/inlayHint` with a plan and the plugin's Code Vision shows it; capabilities sweep per dialect (completion, hover, formatting, rename, highlight, quick fixes); Run shows rows for SQL (key-bounded), PartiQL and OpenSearch; SQL `GROUP BY customerId` gives the columns `["customerid","COUNT(*)"]`; the lens Run of the second statement runs the second statement; cancel; teardown mid-run, then the next run is not refused as busy; `listTables` and `listIndices` answer; unbound Run shows the `profile-required` sentence once; the profile picker connects with the chosen profile; `profile-window-required` shows once; Pick Index writes a listed index; Validate shows one message
- [ ] cases from `live-launcher*.test.ts` and `live-coordinated.test.ts`: headless start; the app outlives the IDE process (reuse by a second IDE run); nudge (a second headless launch exits 0); integration-off shows its sentence once, and switching it off ends a live connection; killed app (stale file) → Reconnect; restarted app → reconnects by itself; quit → no respawn; signed-out ends the connection; version check (with the min-version override)
- [ ] run `./gradlew testLive` against an app ≥ 1.19.0

### Task 18: CI and gate

**Files:**
- Create: `.github/workflows/ci.yml`, `.github/workflows/release.yml`
- Modify: `build.gradle.kts` (`gate` task, `verifyPlugin` IDE list, signing and publishing config from env)

- [ ] `gate`: build, unit, integration, `verifyPlugin` (no network drift check)
- [ ] `verifyPlugin` on IntelliJ IDEA and DataGrip at the minimum version and latest
- [ ] `ci.yml`: gate and the drift-check job on push and pull request; actions pinned by SHA
- [ ] `release.yml`: on `v*` tag, sign and `publishPlugin` (secrets `PUBLISH_TOKEN`, `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD`); fails loud when a secret is missing
- [ ] run `./gradlew gate` locally

### Task 19: Verify acceptance criteria

- [ ] every VS Code 0.1.1 feature has its JetBrains form (README list, actions, explorer, results, profiles, settings)
- [ ] every row of the reference regression map has its counterparts or a written reason
- [ ] run `./gradlew gate`
- [ ] run `./gradlew testLive`
- [ ] manual `runIde` check in IDEA and DataGrip: lens row, Cmd+Enter, Cmd+Shift+Enter, explorer, results, status widget

### Task 20: [Final] Update documentation

- [ ] `README.md` (STE): requirements (minimum IDE version, app 1.19.0), Editor integration switch, file types, use, profiles, background app, free and paid, settings, privacy (incl. the residual risk), platforms (no remote, WSL, Light Edit), licence
- [ ] `CHANGELOG.md` `## 0.1.0`
- [ ] `docs/publishing.md`: Marketplace, signing, tag release, drift check, live tests
- [ ] update `CLAUDE.md` if new patterns appeared

## Post-Completion

*Each item needs the user's approval. No outward step without it.*

**Decisions** (2026-11-08)
1. Minimum IDE version 2026.3: scratch files and files outside the project are served.
2. The token in the Services LSP console and LSP debug logs is documented in README › Privacy
   (random per app start, valid only on 127.0.0.1). No JetBrains issue.

**Decisions** (2026-11-09, after the Task 1 spike)
3. Platform Restart vs Stop: an unrequested `serverStopped(true)` from the current descriptor sets
   `disconnected(stopped)` at once (not latched). A later `serverInitialized` from that same, not retired
   descriptor sets `connected` again. No timer. Replaces the "same descriptor back in `getClients`" rule.
4. Quick fixes outside project content: add our own quick fix (`createQuickFix` override that applies
   the server's edits to the current document). Task 10.
5. Not adopted: an IPv6-loopback status sentence and README line.
7. Launch form (after the real-app check of point 9): on macOS and Linux spawn through
   `/usr/bin/perl -MPOSIX -e 'POSIX::setsid(); exec @ARGV' <app> --headless`; when `/usr/bin/perl` is
   missing, spawn plainly and log it. Windows: unchanged. Task 7.
6. `didOpen` observation (the user asked for the optimal solution; chosen after the live check): a
   command first checks locally that the file is open in an editor or unsaved, supported and trusted
   (else our "has not opened this file" sentence at once). Then it waits for the server's own
   `publishDiagnostics` for that URI on the current session (seen by the wrapped handler; the app sends
   it for every opened document, also an empty one), up to 3 s, and then sends anyway. If the server did
   not have the document, it refuses with its own clear `run-failed` sentence, which the plugin shows.
   No internal API, no ordering guess. Residual: PROTOCOL.md does not promise publish-on-open; if a
   future app stops it, a command waits up to 3 s once per file and session.

**GitHub**
- Create the public repo `dynotable/jetbrains-dynotable` and push `main`.
- Add the release secrets (from the keychain, never printed).

**JetBrains Marketplace**
- Vendor account "DynoTable" on the product's own account.
- Plugin signing certificate; keys in the keychain and GitHub secrets.
- First upload by hand; moderation takes about 1 to 2 working days. Then tag `v0.1.0` publishes.

**dynotable.com (dynotable-web)**
- A JetBrains section on `/docs/editors`, with screenshots the user approves first.
- Changelog entry.

**Social**
- A post only after the user has seen the exact media (memory: media-before-post).

**Manual verification**
- Windows and Linux are not verified (same as VS Code).
- Trello: no DynoTable board is known; the `feature-card` skill is not installed in this session.
