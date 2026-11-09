# Publishing

## Before the first publish

- DynoTable 1.19.0 or later is released. `MIN_APP_VERSION` in
  `src/main/kotlin/com/dynotable/connect/Discovery.kt` is that version. The README, `CHANGELOG.md`
  and the `plugin.xml` description state the same version.
- [dynotable.com/docs/editors](https://dynotable.com/docs/editors) has a JetBrains section. The
  README, **Open DynoTable Documentation** and the **Open Documentation** action on a DynoTable
  message link to that page.
- The repository `dynotable/jetbrains-dynotable` is on GitHub, with the four release secrets (see
  [Accounts and signing](#accounts-and-signing)).
- Check Windows and Linux on real machines before the README or the listing claims them.

## Gate

```sh
./gradlew gate
```

The gate runs the build, the unit and integration tests, and `verifyPlugin`. The integration tests
are headless: no window opens. `verifyPlugin` checks IntelliJ IDEA and DataGrip at the minimum
version (`platformVersion` in `gradle.properties`) and at their latest release. The gate does not
run the live tests or the drift check. CI runs the gate and the drift check on each push and pull
request (`.github/workflows/ci.yml`).

## Drift check

`PROTOCOL.md`, `contract/` and the two grammars in
`src/main/resources/textmate/dynotable/syntaxes/` are copies from
[vscode-dynotable](https://github.com/dynotable/vscode-dynotable) `main`. The drift check fails when
a copy differs, is missing, or has no upstream file:

```sh
bash scripts/drift-check.sh
```

When it fails, copy the files from vscode-dynotable `main`, then run the gate. A change to
`PROTOCOL.md` or `contract/` can also need a change in the plugin. `DRIFT_UPSTREAM` and `DRIFT_REF`
select another repository and ref.

## Live tests

The live tests run the plugin against a real DynoTable app. They run only on macOS and only on
your computer, never in CI. On other systems they are skipped. A live run counts only when it shows
0 skipped tests.

### Never a build that can update itself

Do not use a released app. Its updater can restart the app without the wrapper's flags, and then the
app runs on your real user data directory. Use a development build of the app that cannot update
itself.

### The wrapper

The plugin starts the app through a wrapper: an executable script that runs the development build.
The wrapper must:

- be outside app bundles and outside `/Applications`;
- give the app `--user-data-dir=<test dir>`;
- set the app's user data directory override environment variable to the same `<test dir>`;
- pass on all its arguments (the plugin starts `<wrapper> --headless`).

### The test user data directory

Prepare `<test dir>` in the app, through the wrapper:

- the Editor integration is on;
- you are signed in, with a trial or paid licence;
- more than one profile, and no app window is open on the profile under test;
- that profile has the tables `orders` (with `customerId`, `orderId` and `status`) and `customers`,
  a table large enough that a run is still in flight when its cancel arrives, and at least one
  OpenSearch index.

### Prove the isolation

Do this before the first live run, and again after you change the build or the wrapper:

1. Make sure no DynoTable app runs.
2. Start `<wrapper> --headless`.
3. Run `lsof -nP -p <app pid>`. No open file is under the real user data directory
   (`~/Library/Application Support/DynoTable`). Open files are under `<test dir>`.
4. Make sure the port file appears in `<test dir>` and not in the real user data directory.

The live tests also check this once, before the first test, and stop when they find a leak.

### Run

| Variable                            | Value                                                                    |
| ----------------------------------- | ------------------------------------------------------------------------ |
| `DYNOTABLE_LIVE_APP_PATH`           | The absolute path of the wrapper.                                        |
| `DYNOTABLE_LIVE_USER_DATA_DIR`      | The absolute path of `<test dir>`.                                       |
| `DYNOTABLE_LIVE_PROFILE`            | The name or slug of the profile under test.                              |
| `DYNOTABLE_LIVE_LARGE_TABLE`        | The large table.                                                         |
| `DYNOTABLE_LIVE_DENY_USER_DATA_DIR` | Optional. One more directory that the live tests must not use.           |

```sh
DYNOTABLE_LIVE_APP_PATH=<wrapper> DYNOTABLE_LIVE_USER_DATA_DIR=<test dir> \
  DYNOTABLE_LIVE_PROFILE=<name|slug> DYNOTABLE_LIVE_LARGE_TABLE=<large table> ./gradlew testLive
```

The live tests refuse a `<test dir>` that is the real user data directory, or inside it, or around
it. They start only `<wrapper> --headless`, and they stop and start only the apps on `<test dir>`.
One test switches the Editor integration off in `<test dir>` and switches it on again at the end.
Run `testLive` apart from `gate`: Gradle refuses the two in one run.

## Accounts and signing

- **JetBrains Marketplace:** the vendor `DynoTable` at
  [plugins.jetbrains.com](https://plugins.jetbrains.com). Make a permanent token for it under
  **My Tokens**.
- **Plugin signing:** a private key and a certificate chain, as JetBrains'
  [plugin signing guide](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html) tells.

Keep the token, the key and its password in the keychain. Do not write them into a file in this
repository and do not print them. The build reads them from four environment variables. The GitHub
repository has them as secrets with the same names:

| Secret                 | Value                             |
| ---------------------- | --------------------------------- |
| `PUBLISH_TOKEN`        | The Marketplace token.            |
| `CERTIFICATE_CHAIN`    | The certificate chain (PEM text). |
| `PRIVATE_KEY`          | The private key (PEM text).       |
| `PRIVATE_KEY_PASSWORD` | The password of the private key.  |

## First upload

The Marketplace takes the first version of a plugin only by hand.

1. Do the steps of [Each release](#each-release) up to the tag. Do not push a tag yet.
2. Set the three signing variables in your shell and run `./gradlew signPlugin`.
3. Upload `build/distributions/jetbrains-dynotable-<version>-signed.zip` on the Marketplace.
4. Wait for the moderation. It takes about 1 to 2 working days.
5. Tag the same commit `v<version>`, as in step 5 below. The release workflow then fails at the
   upload because the version is already on the Marketplace. This is expected one time.

## Each release

1. Set `pluginVersion` in `gradle.properties`.
2. Put the release date on the `CHANGELOG.md` heading. Write the same changes in `<change-notes>`
   in `src/main/resources/META-INF/plugin.xml`.
3. Run the gate, the drift check and the live tests. Each must pass.
4. Commit and push.
5. Tag the commit `v<version>` and push the tag.

The tag starts `.github/workflows/release.yml`. It first fails when a secret is missing, so it never
uploads an unsigned plugin. Then it runs `./gradlew gate publishPlugin`, which signs the plugin and
uploads it. Each upload goes through the Marketplace moderation again.
