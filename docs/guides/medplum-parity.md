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
| V5a | Policy/subscription criterion with an **empty parameter value** (`Observation?status=`) | Accepted. Medplum's matcher treats it as a parameter with no value to compare, so it does not filter. HAPI 8.12.1 agrees: `canBeEvaluatedInMemory` reports **supported** and `match` returns **true for every resource of the type** (measured, issue #7). | **Rejected at write time**, 400 with an `OperationOutcome` naming the parameter; if one reaches the compiler anyway it becomes `Criteria.Never`. **Deliberately stricter than both.** A criterion like this reads restrictive and grants the whole resource type — from a typo, or from a parameter substitution that resolved to nothing. Failing open is not an acceptable default for an access policy. |
| V5b | `:in` / `:not-in` in a criterion | Degraded to equality (`:not-in` inverts) — D15. | **Rejected at write time.** HAPI 8.12.1 reports them *supported*, but the ValueSet is not expanded: `:in` matched nothing and `:not-in` matched everything against a code that is in the set (measured, issue #7). Silently inverted access is worse than refused. **Lift condition, v0.2 candidate (ADR-001 Open):** a test proving expansion works. |
| V5c | `:missing` in a criterion | Supported; D15 and ADR-001 both named `:not`/`:missing` as *the* evaluable pair. | **Not evaluable on HAPI 8.12.1** — `Qualified parameter not supported` — so rejected at write time. ADR-001 and this row amended 2026-09-22. |
| T1a | Who may author an `AccessPolicy` | **Any member with a `*` policy.** `AccessPolicy` is not in Medplum's project-admin type list, and a membership with no policy compiles to `{resourceType:'*'}`, so an ordinary member can create one. Measured on the running stack: `POST /fhir/R4/AccessPolicy` as a non-admin member returned **201**. | **A project-admin type (T1).** A `*` entry no longer covers it; `membership.admin` reaches it through the injected admin-type rules, with every interaction (unlike `Project`/`User`/`ProjectMembership`, which are read-and-update — administering policies without being able to write one is broken, not narrower). Authoring is not attaching, so this was not a completed escalation, but a member who can author the resource that constrains them is one `ProjectMembership` bug away from choosing their own access, and T1 exists so that assumption is not load-bearing. Ruled 2026-09-24. |
| V2a | `_filter` with `not (…)` | Supported — Medplum's `_filter` builds a full expression tree. | **Rejected**: `HAPI-1056: Expression did not terminate`. Fails as `not (…)` and `not(…)` alike, so it is a capability gap rather than a spelling. `ne` covers the common negation (measured, #9). |
| V2b | `_filter` with a dotted path (`subject.name eq "x"`) | Supported — Medplum recurses into the full search builder, so a `_filter` can chain. | **Rejected**: `HAPI-1206: Unknown search parameter "subject"`. HAPI's `_filter` does not chain. Ordinary chained search (`subject.name=x`, outside `_filter`) works and is the documented alternative (measured, #9). |
| V2c | `_filter` availability | On. | **Off in HAPI by default** (`HAPI-1222`), enabled by Big Book. Not a divergence in behaviour — recorded because a stock HAPI does not answer `_filter` at all. |
| — | `:missing` and `:contains` on search | Supported. | Supported, but **off in HAPI by default** and enabled by Big Book. Worth recording for the failure mode: without the missing-field index a `:missing` search does not 400, it builds malformed SQL and **500s** (`Columns used for unreferenced tables [HFJ_SPIDX_DATE]`), and `:contains` returns **405**. Both measured on #9. **Note:** enabling the index does *not* make `:missing` evaluable by `InMemoryResourceMatcher`, so V5's write-time rejection of `:missing` in an `AccessPolicy` criterion stands (ADR-001). |
| — | `:above` / `:below` on a plain token parameter | Not supported (Medplum 400s — T23 says not to assert those 400s). | **Also 400 on HAPI 8.12.1** (measured, #9). The task listed these as a gain from HAPI; they apply to hierarchical and URI parameters, not to every token, so for `Observation?code` there is no gain to record. Noted so the T23 line is not read as Big Book answering them. |
| — | `:not` semantics | Excludes resources matching the value. | Same, and stated because it surprises: HAPI's `:not` requires the parameter to be **present with a different value**. `family:not=Flanders` returns **nothing** when no resource carries a `Flanders` family at all, rather than every Patient not called Flanders. That is the documented FHIR reading; measured on #9, where it caught a wrong expectation in Big Book's own test rather than a defect. |
| — | Runtime `SearchParameter` registration | Boot-time bundles only. | **Runtime, and picked up without `$reindex`** (measured, #9 V4). Big Book had to route HAPI's non-partitionable types to the default partition first: a tenant partition on `SearchParameter` is refused with `HAPI-1318`. **Consequence: these types are shared across tenants**, because HAPI's search index is server-wide and it refuses to partition them. Writing them is still governed by the policy layer, so an ordinary member cannot register one. |
| D57 | Subscription delivery vs the author's `AccessPolicy` | **The check is a no-op.** Medplum resolves the subscription author's policy and then does not apply it to the delivered resource, so a restricted author's subscription delivers resources they could not read over REST. | **Enforced.** The author's criteria suppress firing at `SUBSCRIPTION_RESOURCE_MATCHED` and their `hiddenFields` are stripped from the payload at `SUBSCRIPTION_BEFORE_REST_HOOK_DELIVERY`. Delivery is the one path a resource leaves the server without passing `PREACCESS`, so without this a subscription is a way around the whole read path. Fails closed: an unresolvable author does not fire (issue #7; the author is recorded by #12). |
| — | `AccessPolicy` storage | A first-class table in Medplum's own schema. | A Big Book Postgres table partitioned by `project_id`, served by a plain `IResourceProvider` rather than HAPI JPA. Not a divergence in behaviour — the resource is readable and writable at `/fhir/R4/AccessPolicy` in Medplum's JSON shape — but the reason is worth recording: HAPI JPA has no DAO for a runtime-registered `@ResourceDef` type (`HAPI-0572`), because it generates DAOs and search indexes from the structures it ships (spiked on #7). The same pattern serves the other Medplum admin types in v0.2 (ADR-003). |
| D16 | Cross-project references | `checkReferencesOnWrite` off by default; a resource may store a reference into another project. | `enforce_referential_integrity_on_write` on by default; per-project switch kept. |
| D17 | Paging | Cursor `2-<epochMillis>-<uuids>` (docs call it opaque), engaged under four simultaneous conditions; page links re-serialised, dropping `_summary`/`_format`/`_pretty` (`search.ts:568-576, 664-671`). | Offset paging byte-compatible; links echo caller params; cursor token not wire-compatible (HAPI paging cache). |
| D18 | GraphQL limits | `graphqlMaxDepth` and the query-cost rule enforce nothing — `reportError` calls commented out (`graphql.ts:542-549, 639-643`); `graphql-introspection` project feature read by no server code. | Depth/cost limits enforcing; introspection toggle server-wide (v0.1). |
| D19 | Quantity / number search | Quantity discards system and code (`search.ts:1491`); number has no precision range (`range-column.ts:279` TODO). | HAPI semantics: units matched, precision ranges applied. Fewer results than Medplum. |
| D34 | Bootstrap | Super-admin password silently defaults to `medplum_admin` (`seed.ts:63-64`); `registerEnabled` defaults on; shipped config `allowedOrigins: "*"`. | No default password (generated, printed once); registration off; CORS echoes origin with credentials. |
| D57 | Rest-hook policy check | `satisfiesAccessPolicy` builds the author's policy then `return channel.type === 'websocket' ? satisfied : true` (`workers/subscription.ts:236-308`). | Enforced for every channel from v0.1. |
| V1 | Policy criteria on results reached indirectly (`_include`/`_revinclude`, GraphQL nested references and lists) | Criteria are pushed into SQL for every query, include sub-searches and GraphQL resolvers included (`repo.ts:1863-1930`, `fhir/search.ts:497-514`; inventory REPO-051, SRCH-036), so a row outside criteria is never fetched. | HAPI 8.12.1's `SearchNarrowingInterceptor` narrows the primary query only, and `AuthorizationInterceptor` answers a stray include with 403 for the whole request (issue #9). Big Book narrows every search pre-query at `STORAGE_PRESEARCH_REGISTERED` and drops what still fails criteria at `STORAGE_PREACCESS_RESOURCES` (ADR-001, amended 2026-09-18). Same visible result for collections: filtered silently, never 403. Differences: dropped includes are not refilled; a single `read`/`vread` outside criteria is 404 (to be reconciled against Medplum's status once verified, ADR-001 Open); if `PRESEARCH_REGISTERED` does not fire for GraphQL nested searches, nested lists may be short and connection `count` is the DB count. |
| #4 | GraphQL nested reference into another project | The field comes back empty and the rest of the query is returned. `readReferences` returns an error *per entry* and never fails the batch (`repo.ts:789-852`, inventory REPO-012). The empty-field response shape is as stated by the PO; it was not re-observed for this row. | **404 for the whole query**: `HAPI-1147 … HAPI-2001: Resource Patient/<id> is not known`, an `OperationOutcome`. HAPI 8.12.1's GraphQL storage services read the reference through the DAO in the caller's partition and do not catch not-found. No data from the other project is returned, and the answer is identical to an id that exists nowhere, so existence does not leak either. Only reachable if such a reference exists at all, which HAPI refuses on write (cross-partition references are not allowed). Measured in issue #4 (`TenantIsolationTest`). Softening it to an empty field would be glue on HAPI's GraphQL provider; not done. |
| #8a | Conditional update matching nothing, body carries `id` | **400** `Cannot perform create as update with client-assigned ID` (`fhir-router/src/repo.ts` 349–353; parity row a2). | **Same, 400 with an `OperationOutcome`.** Not HAPI's behaviour: HAPI 8.12.1 accepts it, silently assigns its own id and returns 201. Big Book restores Medplum's rule in `ConditionalUpdateIdFilter`. It is a servlet filter, not an interceptor, because `UpdateMethodBinding` calls `theResource.setId(null)` on a conditional update before the first pointcut fires, so no interceptor can see the id the client sent. Measured in issue #8. |
| #8b | Client-assigned `id` on a plain create (`POST`) | Overwritten with a server UUID, no error (`repo.ts:644-690`, inventory REPO-002). | **Same**: the id is ignored and a server UUID is returned (`resourceClientIdStrategy=NOT_ALLOWED` plus `resourceServerIdStrategy=UUID`). |
| #8c | Validation on write | No profile validation on create or update unless asked for. | **Same.** Worth stating because it surprises: HAPI stores an `Observation` with no `code` happily, and `$validate` reports it as an error only when called. Consequence for tests: a "malformed" entry does not make a transaction fail; a dangling reference does. |
| #8d | `$validate` with a value outside a required binding (e.g. `gender: "not-a-gender"`) | 400. | **400, but from the parser, not the validator**: HAPI's JSON parser rejects an unknown enum value before `$validate` runs (`HAPI-1821`), so the `OperationOutcome` describes a parse failure rather than a binding violation. Same status code, different diagnostics. |
| #8e | Medplum's own `fhir-datastore/creating-data` cURL example, run verbatim | **Fails on `api.medplum.com` itself**: the `-d` argument is not quoted in the published example, so the shell splits and mangles it before curl sends anything. Medplum answers `OperationOutcome` `invalid` / "Content could not be parsed". | **Identical failure**, `OperationOutcome` / `HAPI-1861` parse error. With the `-d` argument quoted, the example works unchanged against Big Book: 201 and the `Practitioner` back. A documentation defect upstream, not a server divergence; recorded so nobody "fixes" Big Book to accept it. |
| T30 | System repository | `getSystemRepo()` obtainable from any repository, no audit trail (`repo.ts`). | Internal, non-token-reachable, every elevation logged. |
| T18/T20/T21 | Supersets | No type/system `_history`; `$validate` type-level only, bare body, no `mode`/`profile`; `$graphql` POST system-level only. | HAPI's type/system history, instance `$validate` with parameters, instance/GET `$graphql` all kept. |
| T23/T24/T27 | Supersets | `:above/:below/:in/:not-in/:of-type` parsed then 400; `_include=*` 400; `SearchParameter` resources loaded from static bundles at boot. | HAPI modifiers, `_include=*`, runtime `SearchParameter` + `$reindex` (V4). |
| T28 | 422 | Effectively unreachable: only `business-rule` maps to 422; `badRequest`/`validationError` emit no `id` and land on 400. | Same as Medplum for compat (v0.2 glue maps HAPI's validation 422 → 400, keeps 422 for `business-rule`). |
| D28 | `$export` async | Ignores `Prefer: respond-async`; unconditionally async; poll URL `/fhir/R4/bulkdata/export/:id`, manifest `requiresAccessToken: false` hardcoded. | Bulk Data IG conformant (HAPI); Medplum poll URLs aliased for SDK-grade (v0.2). |
| T46 | `$validate` body | `validateResource` POSTs a bare resource, not `Parameters`. | Accepted as-is (HAPI takes both). Do not "fix" the SDK path. |
| T52 | `_offset` on `_history` | `readHistory` sends `_offset`, not `_getpagesoffset`. | v0.2 alias (D45). |

## Medplum `search/` doc examples (issue #9)

BB-R-002's acceptance criterion is that Medplum's own documented search examples run unchanged. All four
categories are exercised end to end by `SearchParityTest`, against the stack with tenancy and the policy layer
in the path — the point being that "HAPI supports it" is not the same as "a Big Book caller can do it".

### "Wire" means the component *can* do it, not that it does by default

Three features BB-R-002 specifies as **wire** were not merely unconfigured on HAPI's defaults — they were
absent, broken, or refused:

| Feature | Specified as | On HAPI 8.12.1 defaults | Made to work by |
|---|---|---|---|
| `_filter` | wire | **absent** — `HAPI-1222: _filter parameter is disabled on this server` | `setFilterParameterEnabled(true)` |
| `:missing` | wire | **broken** — not a 400 but malformed SQL and a **500**: `Columns used for unreferenced tables [HFJ_SPIDX_DATE]` | `setIndexMissingFields(ENABLED)` |
| `:contains` | wire | **refused** — `405 Method Not Allowed` | `setAllowContainsSearches(true)` |

Each would have shipped as "HAPI supports it, nothing to do" had the verify-first block not run a request. The
`:missing` case is the sharpest: a default-configuration server answers a perfectly legal search with a 500 and
a SQL fragment, which is worse than an honest rejection.

**The lesson for every remaining `wire` row in REQUIREMENTS.md:** a fill marked *wire* still needs one request
per feature before it can be called done. Wire is a statement about the component's capability, not about its
defaults, and the gap between the two is where a 500 lives.

| Category | Example run | Result |
|---|---|---|
| basic | `Patient?family=Simpson`, `Patient?family:exact=`, `:contains`, `:not`, `:missing` | unchanged |
| chained | `Observation?subject.name=Simpson` (forward), `Patient?_has:Observation:subject:code=` (reverse) | unchanged |
| includes | `_include`, `_revinclude`, `_include=*` (T24), `_include:iterate` | unchanged |
| `_filter` | `eq`, `ne`, `co`, `sw`, `gt`, `and`, `or`, parenthesised groups | unchanged; `not` and dotted paths diverge — rows V2a/V2b above |

Sorting, `_total=accurate`, `_count` with `next`, `_summary` and `_elements` are covered by the same class and
by `SearchPagingSummaryTest`. The issue's exit-test query
(`Observation?subject.name=Simpson&_include=Observation:subject&_sort=-date&_count=…`) runs as written, returns a
page with a followable `next`, and blind `next`-following terminates.

Two notes for anyone reading the issue text alongside this file:

- The issue names `docs/guides/parity.md`; the file is `docs/guides/medplum-parity.md`. Same document.
- The issue lists `:above`/`:below` among the modifiers "gained from HAPI". On a plain token parameter they are
  400 on 8.12.1 — see the row above. Nothing was lost; the gain simply was not there to record.
