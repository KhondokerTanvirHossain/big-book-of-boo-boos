# Medplum parity — observed edge-case behaviour

PO-owned after this first fill. Recorded by issue #1 on 2026-09-12 against `medplum/medplum-server:latest` (5.1.37-a9b62fb, `docker-compose.full-stack.yml`), source read at `main` `fbc8e7b4`. Every row below was **observed** with `curl -i`; source refs are for the code path that produced it. The "HAPI default" column is what HAPI JPA does out of the box and is for the integrator to re-verify on the pinned HAPI version (#8).

Token: super-admin via `POST /auth/login` (seeded `admin@example.com`) + PKCE → `POST /oauth2/token`. Non-super-admin via a `ClientApplication` + `ProjectMembership` in a fresh `Project`, `client_credentials`.

## Status codes and OperationOutcome

| Case | Request | Medplum status | `OperationOutcome.id` | `issue.code` | Message | Medplum source | HAPI default | Compat action (BB-R-014.6, v0.2) |
|---|---|---|---|---|---|---|---|---|
| a. Conditional update, >1 match | `PUT /fhir/R4/Patient?identifier=X`, 2 matches | **412** | `multiple-matches` | `multiple-matches` | Multiple resources found matching condition | `fhir-router/src/repo.ts` 332–370; `core/src/outcomes.ts` 195–207, 492–494 | 412, `issue.code=processing`, "…matched N resources" | Rewrite `id` + `issue.code` |
| a2. Conditional update, 0 matches, body has `id` | same, no match | **400** | — | `invalid`, `expression: [Patient.id]` | Cannot perform create as update with client-assigned ID | `fhir-router/src/repo.ts` 349–353 | 201 (creates with that id, alphanumeric strategy) | Reject; HAPI config `client_id_strategy=NONE` (#8) |
| a3. Conditional update, 0 matches, no `id` | same, no match | **201** + `ETag`, `Location`, `Last-Modified` | (resource) | — | — | `fhir-router/src/repo.ts` 354–355 | 201 | None |
| b. DELETE already-deleted | `DELETE /fhir/R4/Patient/{id}` twice | **200** both | `ok` | `informational` | All OK | `server/src/fhir/repo.ts` 1458–1472 | 200, OperationOutcome "…was already deleted" | Rewrite body to `id=ok` |
| b2. DELETE never-existing | `DELETE /fhir/R4/Patient/{random}` | **404** | `not-found` | `not-found` | Not found | `server/src/fhir/repo.ts` 1466–1471 | 404 | Rewrite body |
| c. GET deleted | `GET /fhir/R4/Patient/{deleted}` | **410** | `gone` | `deleted` | Gone | `server/src/fhir/repo.ts` 765–766; `outcomes.ts` 139–151 | 410, `issue.code=processing`, "Resource was deleted at …" | Rewrite `id` + `issue.code` |
| c2. `_history` of deleted | `GET /fhir/R4/Patient/{id}/_history` | **200** Bundle; first entry `request.method=DELETE`, `response.status=410` | — | — | Deleted on <date> | `repo.ts` 935–956 | 200 Bundle, delete entry present | Verify entry shape |
| d. Update-as-create, **super-admin** | `PUT /fhir/R4/Patient/{fresh uuid}` | **200** (not 201), `ETag`, no `Location` | (resource) | — | — | `repo.ts` 1309–1329, 2213–2215 (`canSetId` = `isSuperAdmin`) | 201 + `Location` | Super-admin only: allow, return 200 |
| d'. Update-as-create, **regular user** | same | **404** | `not-found` | `not-found` | Not found | `repo.ts` 1321–1323 | 201 + `Location` | Reject with 404 (BB-R-001.9) |
| e. Stale `If-Match` | `PUT` with `If-Match: W/"<old versionId>"` | **412** | `precondition-failed` | `processing` | Precondition Failed | `fhirrouter.ts` 381–387; `server repo.ts` 1116–1118 | **409** `ResourceVersionConflictException` | 409→412, rewrite body |
| e'. Current `If-Match` | same with live versionId | **200**, new versionId | (resource) | — | — | — | 200 | None |

Also observed:

- Non-existent id on GET as non-admin → 404 `not-found`. Non-UUID id → 404 on GET, 400 `Invalid id` on PUT. HAPI accepts any `[A-Za-z0-9\-\.]{1,64}`; BB-R-001.9 pins UUID-only.
- Every error body carries `extension[url=https://medplum.com/fhir/StructureDefinition/tracing]` with `requestId`/`traceId`. Big Book emits `X-Trace-Id` (BB-R-014.6); adding the extension is a v0.2 glue decision.
- Successful DELETE is **200 + OperationOutcome** (`id=ok`), not 204. HAPI matches.
- `@medplum/core` helpers (`isOk`, `isNotFound`, `isGone`, `isConflict`, …) switch on `OperationOutcome.id`, not on HTTP status. The v0.2 SDK-grade compat layer must emit those slugs verbatim: `ok`, `created`, `not-found`, `gone`, `precondition-failed`, `multiple-matches`, `conflict`, `unauthorized`, `forbidden`, `bad-request`.
- Medplum `versionId` is a UUID; HAPI's is an integer. `If-Match` values therefore differ in shape but both are opaque to a well-behaved client.

## `@medplum/app` sign-in (issue #1, task 1)

**No configuration path to `signInWithRedirect` or a direct OIDC redirect.** The app's entire config surface is `packages/app/src/config.ts`: `MEDPLUM_BASE_URL`, `MEDPLUM_CLIENT_ID`, `GOOGLE_CLIENT_ID`, `RECAPTCHA_SITE_KEY`, `MEDPLUM_REGISTER_ENABLED`, `MEDPLUM_AWS_TEXTRACT_ENABLED` (Vite `envPrefix`, or `docker-entrypoint.sh` sed at container start). `MedplumClient` accepts `authorizeUrl`/`tokenUrl` but `packages/app/src/index.tsx` never passes them.

External IdP login through the stock `SignInForm` exists via a server-side `DomainConfiguration` resource, but the Medplum server still brokers it: `POST /auth/method` → IdP → `GET /auth/external` (mints a `Login`) → `/signin?login=<id>` → `GET /auth/login/<id>` → `POST /oauth2/token` → `GET /auth/me`. So app-grade compat requires emulating the `Login` protocol regardless. Stays **v1.0** (ADR-003).

First load with empty storage makes no auth calls at all: it fires a batched `POST /fhir/R4/$graphql` search, gets 401, and redirects to `/signin`. No `/.well-known/openid-configuration` or `/oauth2/authorize` is ever requested.
