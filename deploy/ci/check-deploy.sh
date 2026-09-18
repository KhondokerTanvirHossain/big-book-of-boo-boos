#!/bin/sh
# Two checks that keep deploy/ honest (issue #2):
#   1. every ${NAME:-default} image pin in deploy/compose/*.yml equals deploy/versions.env (BB-R-011.6)
#   2. no secret literal in any committed config file (BB-R-011.3)
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
    deploy server/src/main/resources \
  | grep -vE '[:=][[:space:]]*"?(\$\{|\$\$\(cat /run/|/run/)' || true)
if [ -n "$leaks" ]; then
  echo "possible secret literal:"
  echo "$leaks"
  fail=1
fi

[ "$fail" -eq 0 ] && echo "deploy checks passed"
exit "$fail"
