# Contributing

## Prerequisites

- JDK 21 or later. Gradle downloads the toolchain and the IDE.

## Build and test

```sh
./gradlew build      # compile, unit and integration tests, plugin zip
./gradlew test       # unit and integration tests (headless, no window opens)
./gradlew testLive   # @Tag("live") tests against a prepared DynoTable app, local only
./gradlew runIde     # an IDE sandbox with the plugin
```

## Layout

- `src/main/kotlin/com/dynotable/`: the plugin.
- `src/test/kotlin/com/dynotable/`: the tests. A test that needs no IDE does not start one.
- `docs/plans/`: implementation plans.

## Rules

- This repository is public. Write only what the wire protocol (`PROTOCOL.md`) and this plugin
  show. Copy nothing from the code or the docs of the DynoTable app. Do not name the app's modules,
  files or internals, in code, fixtures, docs or commit messages.
- Never point anything at the real user data directory of the app. Tests never start the real app
  and never read the real port file. In test mode, the spawn and the open-external calls throw
  unless a fake binary is set. A test app root replaces every install location. A test user data
  directory replaces the port-file directory.
- Live tests run only against a build that cannot update itself, through a wrapper that pins its
  user data directory. Before the first live test, make sure that the app opens no file in the real
  user data directory and that its port file appears only in the test directory.
- Write docs in ASD-STE100 Simplified Technical English.
