#!/bin/sh
# Tests check-deploy.sh instead of trusting it: a clean copy must pass, and every planted defect must fail.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
failures=0

fresh() {
  rm -rf "$work/tree"
  mkdir -p "$work/tree/server/src/main" "$work/tree/core/src/main"
  cp -R "$repo/deploy" "$work/tree/deploy"
  cp -R "$repo/server/src/main/resources" "$work/tree/server/src/main/resources"
  cp -R "$repo/core/src/main/resources" "$work/tree/core/src/main/resources"
}

# expect <pass|fail> <description>: runs the checker on the working copy
expect() {
  if sh "$here/check-deploy.sh" "$work/tree" > "$work/out" 2>&1; then got=pass; else got=fail; fi
  if [ "$got" = "$1" ]; then
    echo "ok    $2 -> $got"
  else
    echo "WRONG $2 -> $got, expected $1"; sed 's/^/        /' "$work/out"; failures=$((failures + 1))
  fi
}

# This file is inside the tree it scans, so the planted keywords are assembled at run time, never written out.
kw1="PASS""WORD"; kw2="sec""ret"; kw3="TOK""EN"

fresh; expect pass "clean copy of the repository"

fresh; printf '      KC_DB_%s: hunter2\n' "$kw1" >> "$work/tree/deploy/compose/lite.yml"
expect fail "literal in a compose file"

fresh; printf 'ADMIN_%s=hunter2\n' "$kw1" >> "$work/tree/deploy/ci/lite-boot.sh"
expect fail "literal in a shell script"

fresh; printf 'bigbook:\n  keycloak:\n    client-%s: hunter2\n' "$kw2" >> "$work/tree/server/src/main/resources/application.yml"
expect fail "literal in application.yml"

fresh; printf 'API_%s="hunter2"\n' "$kw3" >> "$work/tree/deploy/compose/.env.example"
expect fail "quoted literal in .env.example"

# The accepted limit of the scan, pinned so that changing it is a decision and not an accident.
fresh; printf 'ADMIN_%s=$(cat some-file.txt)\n' "$kw1" >> "$work/tree/deploy/ci/lite-boot.sh"
expect pass "KNOWN LIMIT: a value that is a \$(...) command substitution counts as a reference"

fresh; sed 's/^KEYCLOAK_VERSION=.*/KEYCLOAK_VERSION=0.0.0/' "$repo/deploy/versions.env" > "$work/tree/deploy/versions.env"
expect fail "image pin in versions.env differs from the compose default"

fresh; sed 's/"organizationsEnabled": true/"organizationsEnabled": false/' "$repo/deploy/keycloak/bigbook-realm.json" > "$work/tree/deploy/keycloak/bigbook-realm.json"
expect fail "realm file differs from the copy inlined in lite.yml"

[ "$failures" -eq 0 ] && echo "check-deploy self-test passed" || echo "check-deploy self-test: $failures wrong"
exit "$failures"
