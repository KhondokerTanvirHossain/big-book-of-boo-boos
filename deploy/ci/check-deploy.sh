#!/bin/sh
# Two checks that keep deploy/ honest (issue #2):
#   1. every ${NAME:-default} image pin in deploy/compose/*.yml equals deploy/versions.env (BB-R-011.6)
#   2. no secret literal in any committed config file (BB-R-011.3)
#   3. the realm inlined in lite.yml is deploy/keycloak/bigbook-realm.json, byte for byte
set -eu
cd "$(dirname "$0")/../.."
fail=0

while IFS='=' read -r name value; do
  case "$name" in ''|\#*) continue ;; esac
  for default in $(grep -ho "\${$name:-[^}]*}" deploy/compose/*.yml | sed "s/^\${$name:-//; s/}\$//" | sort -u); do
    if [ "$default" != "$value" ]; then
      echo "version drift: $name is '$value' in deploy/versions.env but defaults to '$default' in deploy/compose/"
      fail=1
    fi
  done
done < deploy/versions.env

# A secret-looking key may only be empty, a ${...} reference, or a path to (or read of) a file under /run/.
leaks=$(grep -rnEi '(password|secret|token|api_?key|private_?key)[a-z0-9_.-]*"?[[:space:]]*[:=][[:space:]]*[^[:space:]]' \
    deploy server/src/main/resources core/src/main/resources \
  | grep -vE '[:=][[:space:]]*"?(\$\$?\{|\$\$\(cat /run/|/run/)' || true)
if [ -n "$leaks" ]; then
  echo "possible secret literal:"
  echo "$leaks"
  fail=1
fi

# lite.yml must stay one self-contained file, so it carries a copy of the realm; the copy may not drift.
inlined=$(awk '/^  realm:$/ {found=1; next} found && /^    content: \|$/ {body=1; next} body && /^      / {print substr($0, 7); next} body {exit}' \
  deploy/compose/lite.yml | sed 's/\$\$/$/g')
if [ "$inlined" != "$(cat deploy/keycloak/bigbook-realm.json)" ]; then
  echo "realm drift: the realm inlined in deploy/compose/lite.yml differs from deploy/keycloak/bigbook-realm.json"
  fail=1
fi

[ "$fail" -eq 0 ] && echo "deploy checks passed"
exit "$fail"
