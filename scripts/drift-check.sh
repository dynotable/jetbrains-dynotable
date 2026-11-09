#!/usr/bin/env bash
# Fails when PROTOCOL.md, contract/ or the two grammars differ from vscode-dynotable main.
# DRIFT_UPSTREAM and DRIFT_REF select another upstream repository and ref (the tests use a local one).
set -euo pipefail

upstream="${DRIFT_UPSTREAM:-https://github.com/dynotable/vscode-dynotable.git}"
ref="${DRIFT_REF:-main}"
root="$(cd "$(dirname "$0")/.." && pwd)"
bundle="src/main/resources/textmate/dynotable/syntaxes"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
git clone --quiet --depth 1 --branch "$ref" "$upstream" "$tmp/upstream"
up="$tmp/upstream"

# Pairs of "upstream path|local path".
pairs=("PROTOCOL.md|PROTOCOL.md")
for g in partiql opensearch; do
  pairs+=("syntaxes/$g.tmLanguage.json|$bundle/$g.tmLanguage.json")
done
for f in $( (cd "$up" && ls contract; cd "$root" && ls contract) 2>/dev/null | sort -u); do
  pairs+=("contract/$f|contract/$f")
done

failed=0
for pair in "${pairs[@]}"; do
  u="${pair%%|*}"
  l="${pair#*|}"
  if [[ ! -f "$up/$u" ]]; then
    echo "extra: $l (not in upstream $u)"
    failed=1
  elif [[ ! -f "$root/$l" ]]; then
    echo "missing: $l (upstream $u)"
    failed=1
  elif ! cmp -s "$up/$u" "$root/$l"; then
    echo "differs: $l (upstream $u)"
    failed=1
  fi
done

if [[ "$failed" -ne 0 ]]; then
  echo "drift: copy the files from vscode-dynotable $ref" >&2
  exit 1
fi
echo "no drift"
