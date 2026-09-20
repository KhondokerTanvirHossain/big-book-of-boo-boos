#!/bin/sh
# Boots `lite` exactly as the two-command install does and times it (issue #2, BB-R-011.1/.5).
# The clock covers `docker compose up` until all three containers are healthy, image pulls and first-boot
# bootstrap included (healthy = /actuator/health/readiness, UP only after bootstrap). Then a second start.
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
# the admin console is operator-only now: reachable on the loopback port, and NOT on Big Book's origin
curl -fsSL -o /dev/null http://127.0.0.1:9080/admin/master/console/ \
  || { echo "FAIL: Keycloak admin console not reachable on its operator-only address"; exit 1; }
[ "$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/admin/master/console/)" = "404" ] \
  || { echo "FAIL: Keycloak's admin console must be 404 on Big Book's origin (ADR-003 allow-list)"; exit 1; }
curl -fsS -o /dev/null "http://localhost:8080/realms/bigbook/.well-known/openid-configuration" \
  || { echo "FAIL: Keycloak's realm pages must be served through Big Book's origin"; exit 1; }
curl -fsS -o /dev/null http://127.0.0.1:9080/realms/bigbook \
  || { echo "FAIL: realm bigbook was not imported"; exit 1; }
compose logs bigbook | grep -q 'Bootstrap complete' \
  || { echo "FAIL: healthy without a completed bootstrap"; exit 1; }
compose logs bigbook | grep -q 'Generated super-admin password' \
  || { echo "FAIL: no BIGBOOK_ADMIN_PASSWORD was given, yet none was generated and logged"; exit 1; }

# Second start (issue #3): restart all three containers, keeping their logs, and require that nothing
# changed in any of the three stores and that the generated password was not logged a second time.
snapshot() {
  token=$(curl -fsS -X POST http://127.0.0.1:9080/realms/master/protocol/openid-connect/token \
      -d grant_type=password -d client_id=admin-cli -d username=admin \
      --data-urlencode "password=$(compose exec -T postgres cat /run/bigbook/keycloak-admin-password)" | jq -r .access_token)
  echo "# keycloak realm export"
  curl -fsS -X POST -H "Authorization: Bearer $token" \
      "http://127.0.0.1:9080/admin/realms/bigbook/partial-export?exportClients=true&exportGroupsAndRoles=true" | jq -S .
  echo "# keycloak users and organisations"
  curl -fsS -H "Authorization: Bearer $token" http://127.0.0.1:9080/admin/realms/bigbook/users | jq -S 'map({id, username})'
  curl -fsS -H "Authorization: Bearer $token" http://127.0.0.1:9080/admin/realms/bigbook/organizations | jq -S 'map({id, alias})'
  echo "# hapi partitions, bigbook rows"
  compose exec -T postgres psql -U bigbook -At \
      -c "SELECT part_id, part_name FROM hapi.hfj_partition ORDER BY 1" \
      -c "SELECT * FROM bigbook.project ORDER BY id" \
      -c "SELECT * FROM bigbook.project_membership ORDER BY id"
}
before=$(snapshot)
# an empty or partial snapshot would compare equal to itself and prove nothing
printf '%s\n' "$before" | grep -q '"realm": "bigbook"' && printf '%s\n' "$before" | grep -Eq '^[0-9]+\|[0-9a-f-]{36}$' \
  || { echo "FAIL: could not read the stores to take a snapshot"; exit 1; }
compose restart
compose up -d --wait --wait-timeout "$LIMIT" || { compose ps; compose logs --tail 80; exit 1; }
after=$(snapshot)
if [ "$before" != "$after" ]; then
  echo "FAIL: the second start changed something"
  printf '%s\n' "$before" > /tmp/lite-before.txt; printf '%s\n' "$after" > /tmp/lite-after.txt
  diff /tmp/lite-before.txt /tmp/lite-after.txt || true
  exit 1
fi
completed=$(compose logs bigbook | grep -c 'Bootstrap complete' || true)
generated=$(compose logs bigbook | grep -c 'Generated super-admin password' || true)
[ "$completed" -eq 2 ] || { echo "FAIL: expected bootstrap to run on both starts, saw $completed"; exit 1; }
[ "$generated" -eq 1 ] || { echo "FAIL: the generated password must be logged exactly once across two starts, saw $generated"; exit 1; }
second="second start: bootstrap ran again, nothing changed in Keycloak, HAPI or Big Book ($(printf '%s\n' "$after" | wc -l | tr -d ' ') snapshot lines identical); generated password logged once across both starts"
echo "$second"

result="lite boot: ${elapsed} s to three healthy containers, bootstrap included (limit ${LIMIT} s)"
echo "$result"
[ -z "${GITHUB_STEP_SUMMARY:-}" ] || printf '### %s\n\n%s\n' "$result" "$second" >> "$GITHUB_STEP_SUMMARY"
[ "$elapsed" -le "$LIMIT" ] || { echo "FAIL: over the ${LIMIT} s limit"; exit 1; }
