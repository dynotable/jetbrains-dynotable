# Contributing

## Prerequisites

- JDK 21 or later. Gradle downloads the toolchain and the IDE.

## Build and test

```sh
./gradlew build      # compile, unit and integration tests, plugin zip
./gradlew gate       # build and verifyPlugin: the CI check, no live tests
./gradlew test       # unit and integration tests (headless, no window opens)
./gradlew testLive   # @Tag("live") tests against a prepared DynoTable app, local only (see below)
./gradlew runIde     # an IDE sandbox with the plugin, set to reach no installed app
bash scripts/drift-check.sh  # the copied files against vscode-dynotable main (CI runs it too)
```

Before `testLive`, set up the wrapper, the `DYNOTABLE_LIVE_*` variables and the isolation proof in
[docs/publishing.md › Live tests](docs/publishing.md#live-tests). A live run counts only with 0
skipped tests. Do not run it in the same Gradle call as `gate`.

## Layout

- `src/main/kotlin/com/dynotable/`: the plugin.
- `src/main/resources/textmate/`: the TextMate bundle with the grammars (copies, see Rules).
- `src/test/kotlin/com/dynotable/`: the tests. A test that needs no IDE does not start one.
- `contract/`: the server's answers that the fake server and the contract tests use (copies).
- `scripts/drift-check.sh`: compares the copied files with vscode-dynotable `main`.
- `.github/workflows/`: CI (`gate` and the drift check) and the release on a `v*` tag.
- `docs/publishing.md`: accounts, signing, the live-test rig and the release steps.
- `docs/plans/`: implementation plans.

## Rules

- This repository is public. Write only what the wire protocol (`PROTOCOL.md`) and this plugin
  show. Copy nothing from the code or the docs of the DynoTable app. Do not name the app's modules,
  files or internals, in code, fixtures, docs or commit messages.
- Never point anything at the real user data directory of the app. Tests never start the real app
  and never read the real port file. In test mode, the spawn and the open-external calls throw
  unless a fake binary is set. A test app root replaces every install location. A test user data
  directory replaces the port-file directory. When a test sets no test directory, the plugin uses a
  path that does not exist.
- The `runIde` sandbox starts with the `userDataDir` setting in `build/runIde-userData`, so it reads
  no real port file and starts no app. Change that setting only to an isolated test app.
- `PROTOCOL.md`, `contract/` and
  `src/main/resources/textmate/dynotable/syntaxes/{partiql,opensearch}.tmLanguage.json` are copies
  from vscode-dynotable `main`. Do not edit them here. Change them upstream, then copy them back.
  `bash scripts/drift-check.sh` compares them.
- Live tests run only against a build that cannot update itself, through a wrapper that pins its
  user data directory. Before the first live test, make sure that the app opens no file in the real
  user data directory and that its port file appears only in the test directory.
- Write docs in ASD-STE100 Simplified Technical English.
