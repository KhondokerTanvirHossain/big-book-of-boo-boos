#!/bin/sh
# Boots `lite` exactly as the two-command install does and times it (issue #2, BB-R-011.1/.5).
# The clock covers `docker compose up` until all three containers are healthy, image pulls included.
# Override BIGBOOK_IMAGE / BIGBOOK_VERSION to test an image that is not published yet.
set -eu
cd "$(dirname "$0")/../.."
LIMIT=600
# the one value an install must supply (issue #3); CI has no .env
export BIGBOOK_ADMIN_EMAIL="${BIGBOOK_ADMIN_EMAIL:-root@bigbook.test}"
compose() { docker compose -f deploy/compose/lite.yml "$@"; }

start=$(date +%s)
compose up -d --wait --wait-timeout "$LIMIT" || { compose ps; compose logs --tail 80; exit 1; }
elapsed=$(( $(date +%s) - start ))

compose ps
healthy=$(compose ps --format '{{.Health}}' | grep -c '^healthy$' || true)
total=$(compose ps -a --format '{{.Name}}' | wc -l | tr -d ' ')
[ "$total" -eq 3 ] || { echo "FAIL: lite must be exactly three containers, found $total"; exit 1; }
[ "$healthy" -eq 3 ] || { echo "FAIL: $healthy of 3 containers healthy"; exit 1; }

curl -fsS http://localhost:8080/fhir/R4/metadata | grep -q '"resourceType": *"CapabilityStatement"' \
  || { echo "FAIL: /fhir/R4/metadata did not return a CapabilityStatement"; exit 1; }
curl -fsSL -o /dev/null http://localhost:8081/admin/master/console/ \
  || { echo "FAIL: Keycloak admin console not reachable"; exit 1; }
curl -fsS -o /dev/null http://localhost:8081/realms/bigbook \
  || { echo "FAIL: realm bigbook was not imported"; exit 1; }
compose logs bigbook | grep -q 'Bootstrap complete' \
  || { echo "FAIL: healthy without a completed bootstrap"; exit 1; }
compose logs bigbook | grep -q 'Generated super-admin password' \
  || { echo "FAIL: no BIGBOOK_ADMIN_PASSWORD was given, yet none was generated and logged"; exit 1; }

result="lite boot: ${elapsed} s to three healthy containers (limit ${LIMIT} s)"
echo "$result"
[ -z "${GITHUB_STEP_SUMMARY:-}" ] || echo "### $result" >> "$GITHUB_STEP_SUMMARY"
[ "$elapsed" -le "$LIMIT" ] || { echo "FAIL: over the ${LIMIT} s limit"; exit 1; }
