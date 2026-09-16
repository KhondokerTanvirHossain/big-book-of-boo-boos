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

## Deliberate divergences (inventory reconcile, 2026-09-17)

Recorded so nobody "fixes" Big Book back to Medplum's behaviour. Principle: BIGBOOK.md "contract, not defects". Source: `docs/inventory/` (frozen at `fbc8e7b4b`), decision numbers as in REQUIREMENTS.md.

| # | Where | Medplum (observed in source) | Big Book |
|---|---|---|---|
| D11 | Transactions | Atomic only when the project has the `transaction-bundles` feature; otherwise a `transaction` bundle silently runs as a batch and still returns `transaction-response`. Caps: 50 updates, 8 entries when any conditional op is present (`fhir-router/batch.ts:873-875, 29-30`). | Always atomic; no feature gate; no caps. |
| D12 | No-op PUT | Byte-identical PUT returns the existing resource, no new version; router surfaces 304; `@medplum/core.upsertResource` branches on it (`client.ts:2425-2429`). | v0.1 versions unconditionally; 304 path is v0.2 glue (BB-R-014.3). |
| D14 | Policy filter on malformed criteria | `addAccessPolicyFilters` `return`s out of the loop, dropping every remaining restriction for the request — fails open (`repo.ts:1901`). | Fails closed: 403 + log. |
| D15 | Criteria the matcher cannot evaluate | `matchesSearchRequest` returns `false` for chained/`_has`/`_filter`/number/quantity, ignores `:exact`, degrades `:in`/`:not-in`/`:above`/`:below`/`:text` to equality (`:not-in` inverts), compares dates lexicographically. Used for `AccessPolicy` write checks and `Subscription.criteria`. | Rejected with 400 when the `AccessPolicy`/`Subscription` is written (shared validator; evaluable subset per V5). |
| D16 | Cross-project references | `checkReferencesOnWrite` off by default; a resource may store a reference into another project. | `enforce_referential_integrity_on_write` on by default; per-project switch kept. |
| D17 | Paging | Cursor `2-<epochMillis>-<uuids>` (docs call it opaque), engaged under four simultaneous conditions; page links re-serialised, dropping `_summary`/`_format`/`_pretty` (`search.ts:568-576, 664-671`). | Offset paging byte-compatible; links echo caller params; cursor token not wire-compatible (HAPI paging cache). |
| D18 | GraphQL limits | `graphqlMaxDepth` and the query-cost rule enforce nothing — `reportError` calls commented out (`graphql.ts:542-549, 639-643`); `graphql-introspection` project feature read by no server code. | Depth/cost limits enforcing; introspection toggle server-wide (v0.1). |
| D19 | Quantity / number search | Quantity discards system and code (`search.ts:1491`); number has no precision range (`range-column.ts:279` TODO). | HAPI semantics: units matched, precision ranges applied. Fewer results than Medplum. |
| D34 | Bootstrap | Super-admin password silently defaults to `medplum_admin` (`seed.ts:63-64`); `registerEnabled` defaults on; shipped config `allowedOrigins: "*"`. | No default password (generated, printed once); registration off; CORS echoes origin with credentials. |
| D57 | Rest-hook policy check | `satisfiesAccessPolicy` builds the author's policy then `return channel.type === 'websocket' ? satisfied : true` (`workers/subscription.ts:236-308`). | Enforced for every channel from v0.1. |
| T30 | System repository | `getSystemRepo()` obtainable from any repository, no audit trail (`repo.ts`). | Internal, non-token-reachable, every elevation logged. |
| T18/T20/T21 | Supersets | No type/system `_history`; `$validate` type-level only, bare body, no `mode`/`profile`; `$graphql` POST system-level only. | HAPI's type/system history, instance `$validate` with parameters, instance/GET `$graphql` all kept. |
| T23/T24/T27 | Supersets | `:above/:below/:in/:not-in/:of-type` parsed then 400; `_include=*` 400; `SearchParameter` resources loaded from static bundles at boot. | HAPI modifiers, `_include=*`, runtime `SearchParameter` + `$reindex` (V4). |
| T28 | 422 | Effectively unreachable: only `business-rule` maps to 422; `badRequest`/`validationError` emit no `id` and land on 400. | Same as Medplum for compat (v0.2 glue maps HAPI's validation 422 → 400, keeps 422 for `business-rule`). |
| D28 | `$export` async | Ignores `Prefer: respond-async`; unconditionally async; poll URL `/fhir/R4/bulkdata/export/:id`, manifest `requiresAccessToken: false` hardcoded. | Bulk Data IG conformant (HAPI); Medplum poll URLs aliased for SDK-grade (v0.2). |
| T46 | `$validate` body | `validateResource` POSTs a bare resource, not `Parameters`. | Accepted as-is (HAPI takes both). Do not "fix" the SDK path. |
| T52 | `_offset` on `_history` | `readHistory` sends `_offset`, not `_getpagesoffset`. | v0.2 alias (D45). |
