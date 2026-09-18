# ADR-001 Policy engine

Status: decided (2026-09-12); amended 2026-09-18 (V1)
Decision: **No external engine.** Medplum-shaped `AccessPolicy` resources are compiled per membership into (a) HAPI `AuthorizationInterceptor` rules — `resourceType × interaction` only — and (b) a Big Book criteria set enforced in HAPI hooks: pre-query narrowing at `STORAGE_PRESEARCH_REGISTERED` (every search, REST or GraphQL-nested), post-query drop at `STORAGE_PREACCESS_RESOURCES` (every returned resource, including `_include`/`_revinclude` and GraphQL references), `hiddenFields` at `STORAGE_PRESHOW_RESOURCES`, `readonlyFields` and the post-write criteria check at `STORAGE_PRESTORAGE_RESOURCE_*`, and subscription-side enforcement on the delivery hooks. Cerbos rejected (adds a container to `lite`); OPA rejected (embeddable, but a second interpreter for a fixed schema that cannot evaluate FHIR criteria or filter fields).
Context: see `docs/BIGBOOK.md` → Open decisions; requirements unblocked: BB-R-006, BB-R-003.5, BB-R-005.3, BB-R-014.3. V1 evidence: issue #9.

## Rationale
- The hard parts of BB-R-006 — search-string `criteria`, `hiddenFields`, `readonlyFields`, parameter substitution — require the resource in the JVM and FHIR search semantics; neither Cerbos nor OPA has them. HAPI does (`InMemoryResourceMatcher`, `FhirTerser`, FhirPath).
- What remains for an engine (type × interaction × admin) is a native HAPI rule list.
- "Policies are data" is preserved: the data is the `AccessPolicy` resource; the translator is schema-fixed code, never per-type `if` statements.
- `lite` stays at three containers.
- (V1) `SearchNarrowingInterceptor` narrows the primary query only, and `FhirQueryRuleTester` fails closed by 403ing the whole request — a criteria-scoped caller could not use `_include` or GraphQL lists at all. Enforcement therefore moves to per-resource drop at `PREACCESS`, which runs before `AuthorizationInterceptor`'s own `PRESHOW` check and is the pointcut HAPI built for dropping.

## Consequences
- **Enforcement path.** `PRESEARCH_REGISTERED` (narrow the `SearchParameterMap`: criteria; ORed policies via `_filter`, else post-only) → `AuthorizationInterceptor` (type × interaction; `allowAll` for super-admin; injected admin-type rules for `membership.admin`; cached per membership on policy versions) → `PREACCESS` (drop resources failing criteria — search entries, includes, GraphQL references, `$everything`, history, transaction responses) → `PRESHOW` (`hiddenFields`) → `PRESTORAGE_*` (`readonlyFields` restore; post-write criteria check → 403) → `SUBSCRIPTION_RESOURCE_MATCHED` / `…BEFORE_REST_HOOK_DELIVERY` (author's policy applied to firing and payload).
- **Collection vs single-resource semantics.** Collections filter silently, never 403. Single `read`/`vread` outside criteria → 404 (HAPI's result for a `PREACCESS` drop; reconcile with `medplum-parity.md`). Writes outside criteria → 403.
- **Paging rule.** Every search is narrowed pre-query, so `_count`, `_total` and `next` are exact; a `PREACCESS` drop on a primary-query entry is a narrowing defect and logs WARN. `_include`/`_revinclude` are outside `_count` and never refilled. GraphQL nested lists are searches and are narrowed at `PRESEARCH_REGISTERED`; if that pointcut fails verification for nested searches, lists may be short and connection `count` is the DB count — record in `medplum-parity.md`.
- **Removed from the design:** `FhirQueryRuleTester`, `SearchNarrowingInterceptor`/`AuthorizedList`. Partition scoping stays with `STORAGE_PARTITION_IDENTIFY_*`.
- **Criteria are validated at write time** against the in-memory matcher's supported subset (`:not`, `:missing`, no chaining — enforced, not conventional); unparseable criteria fail closed (D14, D15).
- **Storage:** `AccessPolicy` served at `/fhir/R4/AccessPolicy` in Medplum JSON from a Big Book Postgres table partitioned by `project_id`, via a plain `IResourceProvider` — same pattern as the other Medplum admin types (ADR-003, v0.2). Not HAPI JPA unless custom `@ResourceDef` types are verified clean on the pinned version.
- **Policy semantics per inventory T1–T3:** only `Project.superAdmin` bypasses; `membership.admin` = own policy + injected admin-type rules; `ProjectMembership.access[]{policy, parameter[]}` concatenates and ORs; built-in parameters are `%profile` and `%patient`; a membership with no policy compiles to full project access.
- **Glue budget ≈ 1.4–1.6k lines:** translator ~400, `PRESEARCH` narrowing ~120, `PREACCESS` drop ~150, `hiddenFields` ~150, `readonlyFields` ~100, post-write check ~50, write-time validation ~60, params ~100, interaction split ~60, subscriptions ~100, denial log ~50, backstop WARN ~20, verification-dependent extras ≤200. Defaults/promote-demote (~150) charged to BB-R-005.
- Deferred v0.2 items unaffected or easier: `writeConstraint` (~100, in-JVM FhirPath), binary security context (same `PREACCESS` path), SMART scopes (scopes → `AccessPolicy.resource[]`, translator unchanged). IP rules are not a policy-engine concern.
- **Exit test additions (BB-R-006):** criteria-scoped caller runs `GET /Observation?_include=Observation:subject` where the Patient is outside criteria → 200, include entry absent; `$graphql` `ObservationList` → only visible entries, no 403; primary-query page length = `_count` with `_total=accurate` matching.

## Reversal cost
Low. The `AccessPolicy` JSON contract and the compile step are the seam: retargeting the ~400-line translator core from HAPI rules to an OPA/Cerbos decision call is the whole change. Criteria drop, `hiddenFields`, `readonlyFields`, `writeConstraint` and subscription enforcement stay in HAPI hooks under any engine. Trigger for reversal: a real need for open-ended ABAC beyond the AccessPolicy schema.

## Open — Claude Code tasks
- (V1 follow-up) Confirm `STORAGE_PRESEARCH_REGISTERED` fires for GraphQL nested searches on 8.12.1 and honours `SearchParameterMap` mutation.
- (V1 follow-up) Confirm a `PREACCESS` drop on a single `read` yields 404; reconcile with `medplum-parity.md`.
- Confirm `InMemoryResourceMatcher` handles `:not` on 8.12.1 (sets the write-time validation subset).
- Confirm whether HAPI JPA accepts a custom `@ResourceDef` type cleanly; if yes, drop the AccessPolicy provider and use JPA.

## Amendments
- 2026-09-18 — V1 (issue #9): enforcement moved from `SearchNarrowingInterceptor` + `FhirQueryRuleTester` to `PRESEARCH_REGISTERED` narrowing + `PREACCESS` drop; collection results filter silently; paging rule stated; glue +≈300.
