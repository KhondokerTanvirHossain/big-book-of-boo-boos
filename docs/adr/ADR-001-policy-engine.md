# ADR-001 Policy engine

Status: decided (2026-09-12), amended (2026-09-17, inventory reconcile — T1, T2, T3, D14, D15/T31, D26/D58, D57)
Decision: **No external engine.** Medplum-shaped `AccessPolicy` resources are translated at request time into HAPI `AuthorizationInterceptor` rules plus `SearchNarrowingInterceptor` narrowing; everything HAPI's rule model cannot express (field hiding, readonly fields, parameter substitution, write-side criteria, subscription-side enforcement) is a Big Book hook on a HAPI pointcut. Write authorisation is **two-phase** (pre- and post-write). Criteria are **validated at write time** and the translator **fails closed**. Budget ≈1.2–1.4k lines in `core/policy/`.
Context: see `docs/BIGBOOK.md` → Open decisions; requirements unblocked by this: BB-R-006 (all), BB-R-003.5, BB-R-007 delivery; issue #7 implements it.

Reconstructed from the Architect's decision as folded into `docs/REQUIREMENTS.md` BB-R-006 (fill line, item 12) and issue #7 on 2026-09-12. If the Architect's original text names different hook names, that text wins; replace this file.

## Options

- **A** — Cerbos via HAPI `AuthorizationInterceptor`. Sidecar container; policies in Cerbos YAML; every request makes an out-of-process check.
- **B** — OPA via HAPI `AuthorizationInterceptor`. Same shape as A with Rego.
- **C** — No external engine. `AccessPolicy` JSON → HAPI rule objects per request, Big Book hooks for the rest. **Chosen.**

## Rationale

- `lite` is three containers (BB-R-011.1, ADR-006). A or B adds a fourth on the request path of every FHIR call.
- Medplum `AccessPolicy.criteria` is a FHIR search string. HAPI already evaluates FHIR search strings, both against the database (`SearchNarrowingInterceptor`) and in memory (`InMemoryResourceMatcher`). A or B would mean translating FHIR search semantics into a second policy language and keeping the two in sync.
- Search narrowing must be pre-query so `_total` and paging are correct (issue #7 AC). Only HAPI can narrow the query; an external engine can only post-filter or duplicate the criteria.
- HAPI's `AuthorizationInterceptor` rule model already covers interaction × resource type × compartment. The remainder is small and local.
- Cost: Big Book owns ≈1.1–1.3k lines of policy code instead of a config-only integration. Accepted; it stays inside the 5–10k budget and is the single largest glue brick.

## Enforcement path

Every request, REST and GraphQL alike, and every subscription delivery, goes through the same steps.

1. **Identity** — bearer token (BB-R-004.9) carries `project`, `profile`, `membership` claims. `X-Project` may select the partition for super-admin tokens only, optional, path id wins (BB-R-005.11).
2. **Membership → policies** — `ProjectMembership.accessPolicy` (0..1) and every `access[].policy` loaded; all entries concatenate and OR-combine (T2). **`admin: true` does not bypass** (T1): only `Project.superAdmin` bypasses. An admin membership gets its own policy plus the fixed injected rule set for the project-admin types (`Project`, `ProjectMembership`, `User`, `Cron`, `Package*`, `UserSecurityRequest` — which `*` never covers), with Medplum's hidden/readonly field lists (BB-R-006.2). A membership with no policy at all compiles to `{resourceType:'*'}` = full project access.
3. **Parameter substitution** — `%profile`, `%patient` (defaults to the profile) and `access[].parameter` values (`%name`, `%name.id`, longer names first) are substituted textually into the policy JSON. There is no `%requestor` (T3).
4. **Translation** — each `AccessPolicy.resource[]` entry becomes:
   - `AuthorizationInterceptor` rules: allow `interaction[]` (default all) on `resourceType` (or `*`), in the caller's partition; `readonly` drops write interactions; `compartment` becomes an `inCompartment` rule.
   - `SearchNarrowingInterceptor` narrowing: `criteria` is appended to every search and `_history`/`$graphql` list query for that type, so the database never returns rows outside the policy. Narrowing must also reach `_include`/`_revinclude` sub-queries (**V1, blocking** — a miss is a cross-tenant leak).
   - A `criteria` that fails to parse at request time **fails closed**: the request is denied with 403 and logged (D14). Medplum drops every remaining restriction instead; Big Book diverges.
5. **HAPI executes** the narrowed, authorised request.
6. **Hooks** (below) apply what rules and narrowing cannot. Writes are authorised **twice**: before the write against the incoming resource, and after the write against the stored result (D14). The second phase is what stops a PUT from moving a resource outside its policy's criteria.
7. **Denials** are raised as 403 with an `OperationOutcome` and logged structured: project, user, resource type/id, interaction, policy id.

**Write-time criteria validation (D15, T31).** `AccessPolicy.criteria` and `Subscription.criteria` are validated when the resource is written, with one shared validator: any criterion the in-memory matcher cannot evaluate correctly is rejected with 400. The evaluable subset is the intersection of Medplum's documented restriction (`:not`, `:missing`, no chaining) and HAPI's `InMemoryResourceMatcher` capabilities (**V5**). The restriction is enforced, not conventional — Medplum's matcher silently returns `false` for chained, `_has`, `_filter`, number and quantity parameters, ignores `:exact`, and degrades `:in`/`:not-in`/`:above`/`:below`/`:text` to equality, so a `:not-in` policy computes its own inverse there.

**Bot identity (D26, D58).** A bot executes under its own membership. `Bot.runAsUser` applies only to `$execute` (the caller's membership). It is not implemented on the subscription trigger, where Medplum resolves it to the triggering resource's author.

A per-membership rule cache holds the translated rules; it is invalidated when any referenced `AccessPolicy` or the `ProjectMembership` changes version (issue #7).

## Hooks (HAPI pointcuts → Big Book classes)

| Pointcut | Class | Does |
|---|---|---|
| `SERVER_INCOMING_REQUEST_POST_PROCESSED` | `PolicyContextInterceptor` | Resolves membership, substitutes parameters, builds or fetches cached rules, registers them for this request. Runs before HAPI's own `AuthorizationInterceptor` and `SearchNarrowingInterceptor`. |
| `STORAGE_PRESTORAGE_RESOURCE_CREATED` / `_UPDATED` / `_DELETED` | `WriteCriteriaHook` | **Phase 1.** Evaluates `criteria` against the incoming (and, for update/delete, existing) resource with `InMemoryResourceMatcher`; the rule model cannot inspect a request body. Deny → 403. |
| `STORAGE_PRECOMMIT_RESOURCE_CREATED` / `_UPDATED` | `WriteCriteriaHook` | **Phase 2 (D14).** Re-evaluates `criteria` against the resource as stored, inside the transaction. Deny → rollback + 403. |
| `STORAGE_PRESTORAGE_RESOURCE_CREATED` / `_UPDATED` on `AccessPolicy` and `Subscription` | `CriteriaValidationHook` | Rejects any `criteria` outside the evaluable subset with 400 (D15). |
| `STORAGE_PRESTORAGE_RESOURCE_UPDATED` | `ReadonlyFieldsHook` | Restores every `readonlyFields[]` value from the existing version into the incoming one before storage; the PUT succeeds and the response body carries the restored values (Medplum behaviour, wire-compat tiebreaker). |
| `STORAGE_PRESHOW_RESOURCES` | `HiddenFieldsHook` | Strips `hiddenFields[]` from every resource returned by read, vread, search, `_history`, `$graphql` (incl. nested references — Open 3) and subscription payloads. |
| `SUBSCRIPTION_BEFORE_DELIVERY` | `SubscriptionPolicyHook` | Evaluates the subscription owner's membership policies against the matched resource; skips delivery on deny (issue #7 AC). Enforced for every channel type — Medplum's equivalent is a documented no-op for rest-hooks (D57, divergence). |
| `SERVER_HANDLE_EXCEPTION` | `DenialLogHook` | Emits the structured denial log line for every 403 raised by the path above. |

Nothing else touches enforcement. GraphQL (BB-R-003.5) rides the same path because HAPI's GraphQL provider goes through the same DAO and interceptor chain.

## Storage of `AccessPolicy`

`AccessPolicy` is served at `/fhir/R4/AccessPolicy` in Medplum JSON shape, partitioned per project (BB-R-006.12). Preferred: a custom `@ResourceDef` type stored by HAPI JPA like any resource, so search, history, versions and partitioning come free. Fallback if HAPI JPA does not accept the custom type cleanly (Open 4): a Big Book table plus an `IResourceProvider` that exposes the same REST surface. Whichever wins is reused for the other Medplum admin types in BB-R-014.3 (v0.2).

## Consequences

- BB-R-006 fill line and items 1, 2, 3, 13, 14 record this decision; issue #7 is the implementation with the line-count report (>1.5k lines needs PO sign-off). Budget raised from ≈1.1–1.3k to ≈1.2–1.4k for the second phase and the criteria validator (2026-09-17).
- Divergences recorded in `docs/guides/medplum-parity.md`: fail-closed on malformed criteria (D14), write-time criteria rejection (D15), rest-hook policy enforcement (D57).
- `lite` gains no container. BIGBOOK stack row "Access policies" is decided.
- `AccessPolicy.basedOn` stored, no behaviour; `writeConstraint` (FHIRPath) deferred to v0.2 as a further hook on the same pointcuts.
- Subscription delivery (BB-R-007) is policy-aware from v0.1.
- If a later requirement needs cross-project or non-FHIR policy (IP rules, SMART scopes), that is Keycloak or Traefik configuration (BB-R-006.8, .10), not this engine.

## Open

Verify on the pinned HAPI version before writing the translator (issue #7 "verify first"); record each as resolved here. A "no" on 1–4 changes the design: stop and raise it.

1. `InMemoryResourceMatcher` handles `:not` on the pinned HAPI version — widened to its full capability set (**V5**), which defines the evaluable subset for write-time validation.
2. `SearchNarrowingInterceptor` accepts arbitrary-query narrowing, not only compartments — and fires for `_include`/`_revinclude` sub-queries (**V1, blocking**).
3. `STORAGE_PRESHOW_RESOURCES` fires for GraphQL nested reference resolution — and `GraphQLProvider` resolves through the DAO/interceptor chain (**V1**).
4. HAPI JPA accepts a custom `@ResourceDef` type cleanly — if yes, `AccessPolicy` in JPA; if not, Big Book table + `IResourceProvider`.
5. ~~Readonly fields: reject or restore?~~ **Resolved 2026-09-12: restore.** Medplum silently restores the original values and wire-compat is the tiebreaker. Issue #7 AC aligned; BB-R-006 fill line stands.
