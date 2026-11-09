@CONTRIBUTING.md

## Agent rules

- The DynoTable app is private. Use only `PROTOCOL.md` for its behaviour. Copy nothing from the
  app's code or docs into this repository, its fixtures or its commit messages.
- Never point anything at `~/Library/Application Support/DynoTable` (or its Windows and Linux
  equivalents). For live tests use an isolated dev build; prove the isolation first.
- No outward step without the user's OK: creating or pushing to the GitHub repository, the
  JetBrains Marketplace, signing keys, the docs site.
- Run `./gradlew gate` to validate (CI also runs `bash scripts/drift-check.sh`).
