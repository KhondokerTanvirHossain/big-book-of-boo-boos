# Big Book — Requirements

> What Big Book does, brick by brick, mapped to Medplum. Says *what* is observable and *which component* fills it — never *how*. How lives in `docs/adr/` and code.
> Lives at `docs/REQUIREMENTS.md`. PO drafts; Claude Code commits and keeps it in sync with ADRs. If code and this file disagree, code wins — then fix this file.
> Medplum reference = `https://www.medplum.com/docs/<path>`; source `packages/docs/docs/<path>` in medplum/medplum. Snapshot: `main` @ `fbc8e7b4b` (2026-09-11).
> **Reconciled 2026-09-17** against `docs/inventory/` (840 rows). Decisions D1–D63 and text fixes T1–T62 applied; each change cites its number. Exceptions from the bless: D9, D23, D54, D56 (see the line).

## Conventions

- **Fill**: `wire` (upstream component already does it — expose + configure), `glue` (Big Book code, counts against the 5–10k budget), `defer` (named version), `non-goal` (stated, with reason).
- **Status**: `draft` → `blessed` → `done`.
- **Diverges**: Big Book deliberately does not match Medplum here (BIGBOOK.md principle "contract, not defects"). Every such line is also a row in `docs/guides/medplum-parity.md`.
- **Verify (V#)**: a question only the pinned HAPI container answers. Listed per brick, tracked as verify-first blocks on the implementing issue.
- Every requirement has an exit test a machine or a human can run in <5 minutes.
- Sub-requirements carry their own fill/version; a brick is done when all its v0.x sub-requirements for that version are done.

---

# v0.1 — `lite` profile

**Exit criterion (from roadmap):** Baymax reads and writes patient records through Big Book using the Java SDK, on a `lite` stack that installed in ≤10 minutes on a laptop.

`lite` = three containers: Postgres + Keycloak + Big Book server (HAPI FHIR JPA embedded in the Big Book JVM, ADR-006). No Redis, no event bus (ADR-002). Nothing else. Every v0.1 requirement must be satisfiable inside that set.

---

## BB-R-001 FHIR datastore
Medplum ref: `fhir-datastore/` (creating-data, reading-data, updating-data, deleting-data, resource-history, fhir-batch-requests, profiles, working-with-fhir); inventory area 2.1–2.2, 3.1
Fill: **wire HAPI JPA** · Version: v0.1 · Displaces: nothing · Status: blessed

What a client can do at `/fhir/R4`:
1. `create` (POST), `read` (GET /Type/id), `vread`, `update` (PUT — new version on every change), `patch` (JSON Patch), `delete` (soft; 410 Gone afterwards, 404 for never-existed; successful DELETE returns 200 + `OperationOutcome`). A byte-identical PUT: Medplum returns the existing resource with no new version and `@medplum/core.upsertResource` branches on the resulting 304; v0.1 versions unconditionally (**diverges**, recorded), the 304 path is v0.2 glue (D12).
2. Conditional interactions: create (`If-None-Exist`), update (`PUT /Type?search` = upsert: one match updates, zero creates, many → **412** `multiple-matches`), delete and patch with the same one/zero/many rule. Conditional update with zero matches: 400 when the body carries an `id`, 201 when it does not. HAPI `allow_multiple_delete=false` (T19).
3. `_history` per resource instance — the Medplum bar (Medplum has no type- or system-level history; `GET /Patient/_history` is 404 there). HAPI's type- and system-level history is a superset Big Book keeps (T18). Every version retained; no pruning short of `$expunge` (BB-R-018, D22).
4. Batch (`type: batch`) and transaction (`type: transaction`) bundles with internal (`urn:uuid`) and conditional references, `ifMatch` version checks, PATCH entries (`Parameters` FHIRPath-patch form; Medplum's base64-`Binary` JSON-Patch convention is recorded, not reproduced — T29). Transactions are **always atomic**, no feature gate, no entry caps (**diverges**: Medplum is atomic only behind the `transaction-bundles` project feature and otherwise silently runs the bundle as a batch, with 50-update / 8-conditional caps — D11).
5. `$validate` against base R4 and any loaded profile; `meta.profile` honoured; `Project.defaultProfile` (see BB-R-005) auto-applied when absent. Must accept the bare-resource body form `MedplumClient.validateResource` posts (T32); HAPI's instance-level `$validate` and `mode`/`profile` parameters are a kept superset (T20).
6. Reference integrity on write — **default on** in Big Book (`enforce_referential_integrity_on_write`), switchable per project via `Project.checkReferencesOnWrite` (**diverges**: Medplum defaults it off — D16). Resources cannot *resolve* references into another project (BB-R-005.1).
7. Async batch bundles (`Prefer: respond-async` + job status) — **defer v0.2** (D62 Batch queue; with BB-R-020's `AsyncJob` shape).
8. Patient `$match` — **non-goal** (no HAPI core support; MDM module out of scope. It *is* a real 500-line Medplum server operation, not a workflow guide — T33). The dedup *pipeline* is the workflow guide.
9. Server-assigned UUID ids only; `PUT /Type/<new-id>` (update-as-create) is rejected as in Medplum (ADR-003): 404 for project users; super-admin may set ids (BB-R-005.7) and gets 200. **Except inside batch/transaction bundles**, where entry ids may be client-assigned to resolve `urn:uuid` references, uniqueness enforced (D13). Observed values in `docs/guides/medplum-parity.md`.
10. `Patient/$everything` — **defer v0.2**, `wire HAPI` (instance and type level, `_since`/`_type`/`_count`); Medplum's `_inlineAttachments`, care-date windowing and cross-compartment chasing are non-goals (D30).
11. Terminology operations (D23, **exception applied**): `ValueSet/$expand` is in **v0.1 only if V8 passes** (HAPI `$expand` works against the base R4 ValueSets out of the box); otherwise v0.2. `CodeSystem/$lookup`, `CodeSystem/$validate-code`, `ValueSet/$validate-code` — v0.2, `wire HAPI`. `$subsumes`, `$translate` — v0.3. Snowstorm is SNOMED CT only and belongs to v0.3 `full` (BIGBOOK stack table). Unknown ValueSet/CodeSystem url returns 400 in Medplum, 404 in HAPI (T36, parity guide).

Verify: **V7** audit `GET /fhir/R4/metadata` on a fresh `lite` stack — every operation HAPI advertises is either intended or denied by the default policy (Medplum advertises one; no client reads `/metadata`). **V8** `$expand` on base R4 ValueSets and the LOINC/RxNorm/ICD-10 content upload story.

Exit test: transaction bundle creating Patient + Observation with a `urn:uuid` reference returns 200; a transaction whose second entry fails leaves no first entry behind (D11); `GET Patient/id/_history` shows 2 versions after a PUT; deleted Patient returns 410; if V8 passed, `ValueSet/$expand?url=http://hl7.org/fhir/ValueSet/administrative-gender` returns 4 codes.

## BB-R-002 Search
Medplum ref: `search/` (basic-search, chained-search, includes, advanced-search-parameters, filter-search-parameter, paginated-search); inventory area 2.3
Fill: **wire HAPI** · Version: v0.1 · Displaces: nothing · Status: blessed

1. All R4 search parameters per resource type; string/token/reference/date/quantity/number semantics; modifiers `:not`, `:missing`, `:exact`, `:contains`, plus `:above`, `:below`, `:in`, `:not-in` gained from HAPI (Medplum parses these and returns 400 — T23; the exit test must not assert Medplum's 400s). Quantity search matches system and code, number search uses implicit precision ranges (**diverges**: Medplum discards quantity units and has no precision ranges; Big Book returns fewer, correct results — D19). Medplum's own `sw` operator and `:present` modifier — non-goal.
2. Forward and reverse chaining (`subject.name`, `_has:`), nested.
3. `_include`, `_revinclude`, `:iterate`, and `_include=*` / `_revinclude=*` (HAPI; Medplum 400s on `*` — T24).
4. `_id`, `_lastUpdated`, `_summary`, `_elements`, `_tag`, `_profile`, `_security`, `_source`, `_total`, `_sort`, `_count` (cap 1000, **inclusive** — `searchResourcePages` sends exactly 1000, T45). `_summary`/`_elements` results carry `meta.tag` `SUBSETTED` (D44 — the SDK refuses to cache tagged resources; without the tag a partial resource poisons its read cache).
5. `_filter` — wire HAPI (`filter_search` enabled). Medplum's `_filter` recurses into the full builder, so dotted paths become chained searches; its documented `in`/`ni` ValueSet operators throw 400 (T25). Which operators and whether dotted paths work on HAPI is **V2**.
6. `_project=<id>` (over `meta.project`) and `_compartment=Project/<id>` (over `meta.compartment`, also usable as a chain link) — **glue**: both map to the tenant partition (T26; see BB-R-005).
7. Paging (D17): offset paging byte-compatible; `next`/`previous`/`self` links present with correct semantics; page links **echo the caller's parameters** rather than re-serialising them (**diverges**: Medplum re-serialises and silently drops `_summary`/`_format`, so page 2 of a summary search returns full resources). Medplum's cursor token (`2-<epochMillis>-<uuids>`, engaged only when `_offset` unset ∧ `_count≥20` ∧ one sort rule ∧ `_lastUpdated` asc) is explicitly **not** wire-compatible; HAPI's paging cache is used. `_offset` on `_history` — v0.2 alias (D45).
8. Full-text `_text`/`_content` — **new capability**, not a deferral of parity: Medplum never implemented them (T22). v0.2, `full` profile, HAPI + OpenSearch (BB-R-021).
9. Custom `SearchParameter` resources registered at runtime with `$reindex` — a free gain over Medplum, which loads them from static bundles at boot and ignores a POSTed `SearchParameter` (T27). **V4**.

Verify: **V1** — **answered 2026-09-18: no** (issue #9): `SearchNarrowingInterceptor` narrows the primary query only, so narrowing is Big Book's `STORAGE_PRESEARCH_REGISTERED` hook and included resources outside criteria are dropped at `STORAGE_PREACCESS_RESOURCES` (ADR-001 as amended; includes are configured here, enforced by BB-R-006); **V2** `_filter` coverage; **V4** runtime `SearchParameter`; **V6** HAPI `_count` cap, default `_total` mode, page-link shape; **V9** `SUBSETTED` tagging on `_summary`/`_elements` (D44).

Exit test: `GET /Observation?subject.name=Simpson&_include=Observation:subject&_sort=-date&_count=10` returns a page with `next` link; blind `next`-following over 1001 Patients terminates after two pages; `_compartment` filters to one tenant only; a `_summary=true` result is tagged `SUBSETTED` and its `next` page is still summary.

## BB-R-003 GraphQL
Medplum ref: `graphql/` (index, connections, mutations); inventory area 2.3 (GQL rows)
Fill: **wire HAPI GraphQL** · Version: v0.1 · Status: blessed

1. `POST /fhir/R4/$graphql`, FHIR GraphQL spec; nested references, list filtering, `_reference` reverse lookup. Medplum registers **only** this route; HAPI's instance-level `/Type/id/$graphql` and `GET` are a kept superset, not a compat bar (T21).
2. Connection-style queries (`PatientConnection { count offset edges }`) — **V3**; if absent, **defer v0.2**, not glue.
3. Mutations (`PatientCreate`, `PatientUpdate`) — same rule as (2), **V3**.
4. Introspection toggle **server-wide** in v0.1 (Medplum's `graphql-introspection` project feature is a dead flag read by no server code; the real control is the server key `introspectionEnabled` — D18); per-project toggle v0.2. Depth and query-cost limits **enforcing** (**diverges**: Medplum's `graphqlMaxDepth` and cost rule are advisory, their `reportError` calls commented out — D18). ~40–60 glue lines; the only DoS control on `$graphql`.
5. Access policies (BB-R-006) apply at field level — same enforcement path as REST. **V1** (does `GraphQLProvider` resolve through the DAO/interceptor chain — a miss is a cross-tenant leak).

Exit test: GraphQL query for a Patient with nested `ObservationList(_reference: subject)` returns data, and hidden elements from the caller's policy are absent; a query nested past the depth limit is rejected, not logged.

## BB-R-004 Authentication
Medplum ref: `auth/` (index, medplum-as-idp, external-identity-providers, domain-level-identity-providers, client-credentials, client-assertion, token-exchange, mfa, how-mfa-works, logout, session-management, pre-authorized-code, on-behalf-of, direct-external-auth, mtls); `api/oauth/*`; `api/auth/*`; inventory area 1.3, 5.1
Fill: **wire Keycloak** (+ glue for Medplum-shaped endpoints, scope per ADR-003) · Version: v0.1 · Status: blessed

1. Big Book as IdP: email/password login, registration, password reset/set, email verification — Keycloak realm login flows. Admin-initiated variants (force-set password, admin reset link, admin MFA reset — Medplum `ADM-005/026/027/033`) — **defer v0.2**, `wire Keycloak` admin API (D6). Registration **off by default** (Medplum ships it on — D34, area 4 unsafe defaults).
2. OAuth2/OIDC: authorization code + PKCE, refresh (rotation), client credentials, client assertion (private_key_jwt, verified against the client's JWKS URL), logout, session listing/revocation — Keycloak. Token exchange: external-to-internal exchange is a **preview** feature in Keycloak 26 (D9, **exception applied**: preview flag enabled in `full`; ~150 lines of Big Book glue recorded as the expected end state, BB-R-024). `MedplumClient.startJwtBearerLogin` (`grant_type=jwt-bearer`) is **not** a Medplum server feature and is not a compat target (T12).
3. MFA (TOTP) per user; `mfaRequired` at invite pre-provisions the Keycloak `CONFIGURE_TOTP` required action (BB-R-005 invite). Medplum's effective rule is `Project.setting[mfaRequired] OR User.mfaRequired`, project value winning; sibling settings `allowedMfaMethods`, `appName` (T41). Project-wide `mfaRequired` — **defer v0.2** (conditional-OTP flow per organisation, D7). **Email-OTP MFA — non-goal v0.x** (Keycloak has no native email OTP; D7). Note Medplum skips MFA for Google/external/exchange logins.
4. External IdP (Google, Auth0, Cognito, any OIDC/SAML) — Keycloak identity brokering. Domain-level IdP (user@corp.com → corp IdP) — Keycloak organisation domains.
5. Third-party issuers accepted **directly** as bearer tokens (Medplum `externalAuthProviders`) — **defer v0.2**. With Keycloak as the issuer, "direct external auth" is Big Book's baseline, not a deferral (T10).
6. On-Behalf-Of — **defer v0.2** (Keycloak token exchange with `requested_subject`, or a Big Book filter honouring `X-Medplum-On-Behalf-Of` for admins; BB-R-024).
7. mTLS client auth — **non-goal v0.x**.
8. Medplum-shaped endpoints (ADR-003, option B): `/oauth2/authorize`, `/oauth2/userinfo` and `/.well-known/jwks.json` proxied to Keycloak unchanged; `/.well-known/openid-configuration` and `/oauth2/logout` re-shaped by Big Book; `/oauth2/token` proxied unchanged in **v0.1** (curl-level) and **re-shaped from v0.2** — response body gains `project` and `profile` references, which the SDK stores (D3, D51); `/auth/me` **glue** (returns `profile`, `project` {id, name, features, strictMode, superAdmin}, `membership` {id, user, profile, admin}, `config` = a `UserConfiguration` **with a non-empty `menu`**, `accessPolicy` = the **compiled** effective policy — required by the SDK; D4). Token issuer = Big Book public URL. Access-token lifespan **≥ 15 min** in the shipped realm (the SDK refreshes on every request once within its 5-min grace period — D42).
8a. Medplum `Login` resource and the `Login`-protocol routes — **non-goal v0.x**; Keycloak flows replace them. Full list (T11): `/auth/login|login/:id|method|newuser|newproject|newpatient|profile|scope|mfa/*|changepassword|resetpassword|setpassword|verifyemail|google|external|exchange|clientinfo/:id`, `/oauth2/introspect` (Keycloak's own is advertised instead), `/oauth2/register`. `/auth/revoke` and `/auth/preauthorize` are **defer v0.2**, not non-goal.
9. Bearer token on every `/fhir/R4` and `/admin` call. Claims the SDK reads: `exp`, `client_id` (or `cid`), **`login_id`** (without it `@medplum/core` never calls `/auth/me` and behaves as signed-out while holding a valid token), `profile` (D3). Big Book additionally carries `project` and `membership` as mapper claims. 401, never 403, for an expired or invalid bearer.
10. Non-goals confirmed by inventory (T16): project-scoped issuers (`/projects/:id/oauth2`), `Project.site` (per-site Google/reCAPTCHA), `ClientApplication.signInForm`, MCP surfaces.

Exit test: client credentials flow returns a JWT carrying `exp`, `client_id`, `login_id`, `profile`; `/auth/me` under it returns the correct project + profile and a `config.menu` with at least one entry; a user invited with `mfaRequired` cannot complete login without TOTP.

## BB-R-005 Tenancy — Project, User, ProjectMembership, invitations
Medplum ref: `access/projects`, `user-management/` (index, project-vs-server-scoped-users, external-ids, open-patient-registration), `api/project-admin/invite`, `api/project-admin/client`, `self-hosting/project-settings`, `self-hosting/super-admin-guide`; inventory area 1.1–1.2, 4.1
Fill: **glue** (tenant model; provisioning per ADR-007) on Keycloak organisations + HAPI partitions · Version: v0.1 · Displaces: nothing (HAPI has partitions, no project/membership model) · Status: blessed

1. **Project** = isolated container: resources in one project **cannot resolve** references into another (write-time rejection needs BB-R-001.6, default on — D16); each project has its own users, settings, secrets. One project ↔ one Keycloak organisation ↔ one HAPI partition. `resource.meta.project` populated on every resource; in extended mode (`X-Medplum: extended`, the SDK's default) responses also carry `meta.author` and `meta.compartment` with `Project/<id>` (T14; v0.2 glue, BB-R-014.3).
2. **User** identity has scope `server` (devs/admins, many projects) or `project` (clinicians/patients, one project). Practitioner and RelatedPerson default `server`, Patient defaults `project`; any invite carrying `externalId` is forced `project` (T6). Invite of an existing same-scope user reuses it and silently ignores `password`/`mfaRequired`/names; the same email in two scopes in one project → 409 (T6). `User.firstName`/`lastName` are `1..1` — always emitted (T13).
3. **ProjectMembership** links an identity → Project with `profile` (Practitioner | Patient | RelatedPerson | ClientApplication | Bot), `admin` flag, `accessPolicy` (0..1) plus `access[]{policy, parameter[]}` (T2), `userConfiguration`, `invitedBy`. `user` is `User | ClientApplication | Bot`; for clients and bots `user === profile` (self-referential, no User behind them — T7). `Project.owner`'s membership cannot be deleted; deleting the last membership of a **project-scoped** User deletes the User, never the profile (T7). Same user, different privileges per project.
4. **Invite**: `POST /admin/projects/:id/invite` with `resourceType`, `firstName`, `lastName`, `email` **or** `externalId`, `patient` (RelatedPerson), `scope`, `password`, `sendEmail`, `mfaRequired`, `membership: Partial<ProjectMembership>` (wins), `upsert`, `forceNewMembership`; the deprecated top-level `admin`/`accessPolicy`/`access` are still accepted for v0.2 byte-compat (T5). Creates profile resource + membership + Keycloak user (or links existing) and emails (BB-R-010). Response **200** with the `ProjectMembership`, not 201. Order: user → set-password action → profile → membership → email; idempotent on (email, project, scope). Practitioner email-domain allow/block lists (`allowedPractitionerEmailDomain`, server `blockedEmailDomains`) — v0.2.
5. **ClientApplication**: `POST /admin/projects/:id/client` creates a machine identity (Keycloak confidential client) with membership + policy; `ClientApplication.id` **is** the OAuth `client_id` (D4). The secret is **retrievable** by project admins on every read of the resource, as in Medplum and Keycloak (D5; BB-R-013.3 "shows once" dropped). External IDs live on `ProjectMembership.externalId` with the `external-id` search parameter (T8) — v0.1 may keep them on the profile `identifier`, must move by v0.2 SDK-grade.
5a. Routes in (4)–(5) exist in v0.1 with Big Book payloads; byte-compatibility with Medplum's request/response JSON — **defer v0.2** (BB-R-014.3/4). `invite` is the only admin route `@medplum/core` types (SDK-grade needs only it byte-exact — D2).
6. **Project settings** (v0.1 subset, corrected per T9/T37): top-level fields `checkReferencesOnWrite`, `defaultProfile[]`, `features[]`, `defaultAccessPolicies[]{profileType ∈ Patient|Practitioner|RelatedPerson|Admin}` (legacy `defaultPatientAccessPolicy` as fallback — T4); `setting[]` names honoured in v0.1: `mfaRequired`, `appName`; `secret[]`. `rateLimit`, `authRateLimit`, `loginRateLimit` and the four quota keys are **`systemSetting`** (super-admin only), not `setting` (T9) — all → BB-R-023 v0.2. Feature codes honoured in v0.1: `bots`, `cron`, `transaction-bundles` (accepted, ignored — transactions are always atomic), `graphql-introspection` (accepted, ignored — dead flag). Full list in inventory area 4 Table B/C. **Secrets at rest**: in `lite`, `Project.secret` is **plaintext in Postgres and admin-readable**, exactly as in Medplum; Vault (`full`) is the only profile where it is not (D35 — BB-R-011.3 governs values files, a weaker promise).
7. **Super-admin project**: server-wide view; only role that can create projects, override `id`/`meta`, read protected resources. Bootstrapped from env at first start (`BIGBOOK_ADMIN_EMAIL`/`_PASSWORD`); **fail-closed**: no default password — if unset, a random one is generated and printed once (D34; Medplum silently falls back to `medplum_admin`). Big Book's system-level repository context is internal, never token-reachable, and every elevation is logged (**diverges**: Medplum's `getSystemRepo()` is callable from any repository with no audit trail — T30).
8. **Project linking** (shared read-only reference projects, `exportedResourceType`) — **defer v0.2** (HAPI multi-partition read).
9. **Open patient registration** — **defer v0.2** (Keycloak self-registration + `defaultAccessPolicies[Patient]`).
10. SCIM (`api/scim`) — **non-goal v0.x** (Keycloak has SCIM extensions; wire in v1 if asked).
11. **`X-Project` header** (D1): a Big Book extension, **optional, never required**. Super-admin tokens may select the target partition with it; a non-super-admin sending it gets 403. Where a route has a `:projectId` path segment the path wins. A super-admin call without either acts on the super-admin's own project (Medplum behaviour). `@medplum/core` never sends it. ~30 glue lines. (ADR-004 consequence #4 "write without it gets 400" is withdrawn.)
12. Admin routes that exist in Big Book but **not** in Medplum, labelled **BB-only** and never documented as Medplum-compatible (D2): `GET /admin/projects`, `GET/PUT /admin/projects/:id`, `GET /admin/projects/:id/members[?profileType=]`, `PUT /admin/projects/:id/members/:mid`. Medplum's real spellings are the v0.2 compat list in BB-R-014.4.
13. **Provisioning visibility** (ADR-007): create-project, invite and client anchor on a Big Book row with `status = provisioning` and reconcile forward on retry, never compensate. A Project or ProjectMembership in `provisioning` returns 404 to every non-super-admin call, issues no tokens, and is listed with its status in `GET /admin/projects` for the super-admin only. Startup reconciler ≈60 glue lines.
14. Keycloak organisation alias and name are both the project id; display name lives in `bigbook.project`. (Keycloak 26.7.4 holds alias *and* name unique, 409 on either; a consequence of ADR-007's stable keys — issue #4.)
15. Emails are normalised to lowercase on every write and lookup. (Keycloak matches emails case-insensitively; without this one user could hold two seats in a project by case. A consequence of ADR-007's stable keys — issue #4.)

Exit test: super-admin creates two projects; invites the same email to both, admin in A and read-only in B; Patient created in A is invisible from B's token; `/auth/me` under each token shows the right membership; a fresh install with no `BIGBOOK_ADMIN_PASSWORD` prints a generated one and refuses `medplum_admin`. ADR-007: stop Keycloak between two steps of a create-project and of an invite; each request returns an `OperationOutcome` and its row is `provisioning`; restart Keycloak; retry the identical request; the row is `active`, exactly one organisation / partition / user / profile exists, and nothing was deleted at any point.

## BB-R-006 Access policies
Medplum ref: `access/` (index, access-policies, admin, multi-tenant-access-policy, ip-access-rules, user-configuration, binary-security-context, tenant-selector, smart-scopes); inventory area 1.2 (RES-001, AccessPolicy elements), 2.2 (REPO-051/053), 2.3 (SRCH-060)
Fill: **glue** (ADR-001, decided 2026-09-12, amended 2026-09-17 and 2026-09-18 after V1: no external engine — Medplum-shaped `AccessPolicy` resources compiled per membership into HAPI `AuthorizationInterceptor` rules, `resourceType × interaction` only, plus a criteria set enforced in Big Book hooks: pre-query narrowing at `STORAGE_PRESEARCH_REGISTERED`, per-resource drop at `STORAGE_PREACCESS_RESOURCES`; field hiding, readonly restore, parameter substitution, write-time criteria validation and subscription-side enforcement as Big Book interceptor hooks; **two-phase** write authorisation; **fails closed**; ≈1.4–1.6k lines) · Version: v0.1 · Displaces: nothing · Status: blessed

1. `AccessPolicy` resource with `resource[]` entries: `resourceType` (or `*` — which never covers the project-admin types `Project`, `ProjectMembership`, `User`, `Cron`, `Package*`, `UserSecurityRequest`; T1), `criteria` (search string), `interaction[]` (create/read/update/delete/search/history/vread), `readonly`, `readonlyFields[]`, `hiddenFields[]`, `compartment`. **Criteria are validated at write time**: any `criteria` the in-memory matcher cannot evaluate correctly is rejected with 400 (D15, T31 — the restriction to `:not`/`:missing` and no chaining is **enforced**, not conventional; the exact evaluable subset is the intersection of Medplum's documented subset and HAPI's `InMemoryResourceMatcher`, **V5**). Malformed criteria at request time **fail closed** (**diverges**: Medplum's filter `return`s out of the loop and drops every remaining restriction — D14).
2. Policies attach via `ProjectMembership.accessPolicy` (0..1) and `access[]{policy, parameter[]}`; all entries concatenate and OR-combine (T2). **`admin: true` does not bypass** (T1): only `Project.superAdmin` bypasses; an admin membership gets its own policy plus a fixed injected rule set for the project-admin types (Project readonly `features/link/systemSetting`, hidden `superAdmin/systemSecret/strictMode`; User hidden `passwordHash/mfaSecret`, readonly `email/emailVerified/mfaEnrolled/project`; ProjectMembership readonly `project/user`). A membership with **no** policy compiles to `{resourceType:'*'}` = full project access.
3. Parameterized policies: built-ins `%profile` and `%patient` (defaults to the profile) plus custom `access[].parameter` (`%name`, `%name.id`) — resolved per membership. There is no `%requestor` (T3). Substitution is textual over the policy JSON, `%name.id` before `%name`.
4. Default policies per project: `Project.defaultAccessPolicies[].profileType ∈ {Patient, Practitioner, RelatedPerson, Admin}`; `$init` seeds Patient, RelatedPerson (parameterised `%patient`) and Admin; promote/demote swap between the Practitioner and Admin defaults runs on the admin member-update route only (T4).
5. `AccessPolicy.basedOn` for template linkage — v0.1 as data only; the compiled policy served by `/auth/me` sets `basedOn` to the source refs.
6. `UserConfiguration` (menu/search shortcuts for the app) — stored, served by `/auth/me` with the default menu generated when absent; UI use in BB-R-013.
7. `writeConstraint` (FHIRPath pre/post conditions) — **defer v0.2**.
8. IP access rules — **defer v0.2** (Traefik middleware or Keycloak condition).
9. Binary security context — **defer v0.2** (with BB-R-009 MinIO).
10. SMART scopes → policy — **defer v0.2** (ADR-005).
11. Emergency/temporary access patterns — docs only.
12. `AccessPolicy` is readable/writable at `/fhir/R4/AccessPolicy` in Medplum JSON shape, partitioned per project (storage per ADR-001; same pattern reused for the other admin types in BB-R-014.3).
13. **Two-phase write check** (D14): authorisation runs before *and after* every create/update/delete, so a write cannot move a resource outside its policy's criteria. Both phases are in ADR-001's enforcement path.
14. **Bot identity** (D26, D58): a bot runs under its own membership by default; `Bot.runAsUser` on `$execute` runs under the caller's membership; on a subscription trigger Medplum resolves it to the *triggering resource's author* — Big Book does **not** implement `runAsUser` on the subscription trigger (privilege-escalation shape; BB-R-016).
15. `AccessPolicy.compartment` on create adds `meta.accounts` — **defer v0.2** with the accounts model.
16. **Result semantics** (ADR-001, amended 2026-09-18 after V1): collection results filter silently; single `read`/`vread` outside criteria → 404; writes outside criteria → 403.

Verify: **V1** — **answered 2026-09-18: no** (issue #9; ADR-001 amended, `SearchNarrowingInterceptor` removed from the design). V1 follow-ups, open: (a) `STORAGE_PRESEARCH_REGISTERED` fires for GraphQL nested searches and honours `SearchParameterMap` mutation; (b) a `STORAGE_PREACCESS_RESOURCES` drop on a single `read` yields 404. **V5** `InMemoryResourceMatcher` capability set.

Exit test: membership with `{resourceType: Observation, criteria: "subject=%patient", hiddenFields:[note]}` — the user reads only their own Observations (including via `_revinclude` from Patient), `note` is absent, PUT on another patient's Observation → 403, a PUT that would change `subject` to another patient → 403 (post-write phase), an `AccessPolicy` with `criteria: "Observation?subject.name=x"` is rejected on write with 400, and an `AuditEvent` records the denial (AuditEvent detail fills in v0.2, denial log in v0.1). ADR-001 amendment (V1): a criteria-scoped caller runs `GET /Observation?_include=Observation:subject` where the Patient is outside criteria → 200, include entry absent; `$graphql` `ObservationList` → only visible entries, no 403; primary-query page length = `_count` with `_total=accurate` matching.

## BB-R-007 Subscriptions
Medplum ref: `subscriptions/` (index, publish-and-subscribe, subscription-extensions, server-scoped-subscriptions); `api/fhir/operations/resend`; inventory area 6.1–6.2
Fill: **wire HAPI matching** (`SubscriptionMatcherInterceptor`, registry) **+ glue delivery** (Postgres-backed delivery table, poller, signature, policy check, AuditEvent — HAPI's own delivery queue is in-memory by default and loses deliveries on restart, and its rest-hook subscriber has no signature, headers, success codes or max attempts; D55) · Version: v0.1 · Status: blessed

1. R4 `Subscription` with `criteria` (search string) and `channel.type = rest-hook`; fires on create/update/delete matching the criteria, scoped to the project. Also suppressed when: `status != active`; the resource is an `AuditEvent`; the Subscription carries `meta.account(s)` and none intersects the resource's (T55). **`criteria` is validated at write time** with the same evaluable-subset rule as BB-R-006.1 — a criterion the matcher cannot evaluate is rejected with 400 rather than silently never firing (D15; **diverges**).
2. Interaction filter — Medplum extension `https://medplum.com/fhir/StructureDefinition/subscription-supported-interaction`, `valueCode`, repeatable, OR across entries, absent ⇒ all three. **Glue over HAPI** (~20 lines), not "HAPI supports" — HAPI's equivalent is R5/`SubscriptionTopic`-shaped (T54).
3. Retry with backoff (D54, **exception applied — numbers pinned, no preamble/delivery split**): default **4** attempts total, honouring the `subscription-max-attempts` extension; delay `min(20 s × 2^(attempt−1) × jitter, 8 h)`, jitter uniform in [0.9, 1.1]; outbound HTTP timeout 120 s. Delivery state lives in a Postgres table (`subscription_delivery`) polled every second with `FOR UPDATE SKIP LOCKED`, so retries survive restart and a second JVM shares the same table (D55; ~150 lines). Delivery status visible as one `AuditEvent` per attempt (`type.code = transmit`, `source.observer = Subscription/<id>`, `outcome` 0/4, `outcomeDesc` "Attempt <n> received status <code>"), searchable per Subscription (ADR-004; BALP/redaction stays BB-R-018 v0.2). Destination `resource` (default) or `log` via the repeatable `subscription-audit-event-destination` extension (T58).
4. Signature header (D56, **exception applied — byte-compatible only, no timestamp variant**): `X-Signature` = lowercase **hex** HMAC-SHA256 over the exact posted bytes, secret from extension `https://www.medplum.com/fhir/StructureDefinition/subscription-secret` (legacy spelling `…/StructureDefinition-subscriptionSecret` also honoured), absent extension ⇒ no header (T56). Documented as replayable. Required for n8n/bot trust.
5. `$resend` — `POST /fhir/R4/:type/:id/$resend`, **project admin or super admin**, body `{interaction?: create|update|delete (default update), subscription?, verbose?}`; on `update` returns **412** if the resource is not at its latest version; re-evaluates criteria rather than blindly re-sending (T57). **Glue** (thin).
6. Expression-based criteria — extension `https://medplum.com/fhir/StructureDefinition/fhir-path-criteria-expression`, `valueString`, `%current`/`%previous` (`{}` when absent), evaluated before the search-criteria match (T59) — **defer v0.2**.
7. AuditEvent destination `log` (the `subscription-audit-event-destination` extension in (3)) — it is the destination of the delivery AuditEvent, not a channel type (T58) — **v0.1** with (3).
8. WebSocket subscriptions — **defer v0.2** (BB-R-025; D29/D61: four moving parts, the `$get-ws-binding-token` JWT mint is unavoidable Big Book glue, `lite` is single-node so no Redis is needed).
9. Server-scoped (cross-project) subscriptions for super-admin — **defer v0.2**.
10. **Policy enforcement on delivery** (D57, **diverges**): a rest-hook Subscription delivers only what its author's `AccessPolicy` permits to read, from v0.1 (Medplum builds the check and then returns `true` for every non-websocket channel — a documented no-op).
11. Delivery guarantees, stated: rest-hook is **at-least-once and unordered**; receivers dedupe on (`X-Medplum-Subscription`, resource id, `meta.versionId`); no dead-letter queue; permanent failure leaves only the AuditEvent trail. Headers on every delivery: `X-Medplum-Subscription`, `X-Medplum-Interaction`, on delete `X-Medplum-Deleted-Resource: <Type>/<id>` with body `{}`, plus `Subscription.channel.header[]` literals (T60).
12. Outbound target allow-list (D37): `bigbook.outbound.allowed-private-cidrs` lets `lite` reach compose-network targets (n8n, mail catcher) without disabling SSRF protection globally (Medplum's `allowUnsafeOutbound` drops every protection at once). ~40 lines.
13. Auto-disable after N consecutive failures in W seconds (`Subscription.status = off`, `Subscription.error`, `SeriousFailure` AuditEvent) — **defer v0.2** (D60; counter as a column on the delivery table, not Redis).
14. Pre-commit (synchronous, in-transaction) bot subscriptions — **non-goal v0.1/v0.2**, v0.3 candidate (D63).

Verify: **V5** matcher capability set (shared with BB-R-006).

Exit test: Subscription `criteria: Patient?`, rest-hook to a local echo server; creating a Patient delivers a signed POST within 5s; an update-only subscription does not fire on create; a Subscription with `criteria: "Observation?subject.name=x"` is rejected on write with 400; a restart between attempt 1 and attempt 2 does not lose the retry; a Subscription owned by a user whose policy excludes the resource does not fire.

## BB-R-008 Low-code automations (n8n)
Medplum ref: `bots/` (as the "automate on an event" capability, low-code path)
Fill: **wire n8n** via BB-R-007 rest-hooks · Version: v0.1 (recipe + example; n8n container only in `full`) · Status: blessed

1. Documented recipe: Subscription → n8n Webhook node → n8n HTTP node writing back through `/fhir/R4` with a ClientApplication token.
2. One shipped example workflow (JSON) in `examples/n8n/`: "on Patient create, send welcome email".
3. Signature verification snippet for the webhook node — hashes the **raw** body before any JSON re-serialisation; handles the delete case (`X-Medplum-Deleted-Resource`, body `{}`); reads `X-Medplum-Subscription` and `X-Medplum-Interaction`; dedupes on (subscription, resource id, versionId) per BB-R-007.11 (T60).

Exit test: import the example into n8n, create a Patient, workflow run shows the payload; delete the Patient, the workflow run shows the delete header and does not error on the empty body.

## BB-R-009 Binary storage
Medplum ref: `fhir-datastore/binary-data`, `fhir-datastore/external-documents`, `self-hosting/presigned-urls`; inventory area 2.1 (FR Binary rows), 5.1 (SDK-074…080)
Fill: **wire HAPI binary storage** (`lite`: database/local; `full`: MinIO) · Version: v0.1 · Status: blessed

1. `POST /fhir/R4/Binary` with raw body + `Content-Type` stores the blob; `GET` returns it with the original content type; range requests supported. The write path streams outside the JSON body parser (Medplum mounts it above all parsers; gigabyte uploads). `?_filename=` honoured (D48; `MedplumClient.createBinary` sends it — a few lines).
2. `Attachment.url` references to `Binary/id` resolve for authenticated callers; `DocumentReference` pattern documented. The SDK attaches `Authorization` only to URLs that are same-origin and under `baseUrl` (T51) — a constraint on where presigned/MinIO hosts may live.
3. Presigned/tokenised download URLs for `<img>`/`<video>` without a bearer header — **defer v0.2** (BB-R-027). Medplum rewrites attachment URLs in every authenticated response body, reading the Binary through the caller's policy.
4. Binary security context — **defer v0.2**; exact wire spelling `X-Security-Context:` header on upload (T51).
5. Auto-download of external attachment URLs into Binary (Medplum `DownloadQueue`) — **defer v0.3** (D62).

Exit test: upload a 5 MB PDF with `?_filename=report.pdf`, reference it from a DocumentReference, download it back byte-identical in both profiles.

## BB-R-010 Notifications
Medplum ref: `user-management/custom-emails`, `user-management/project-smtp`, `self-hosting/sendgrid`, `self-hosting/mailtrapio`
Fill: **wire Keycloak email + Spring Mail** · Version: v0.1 · Status: blessed

1. Invite, password reset, email verification, MFA enrolment emails via server-wide SMTP config. Invite email for an existing user (project-added notice, no set-password link) is Spring Mail glue; new-user invite is Keycloak's `execute-actions-email`.
2. Per-project SMTP and branded templates — **defer v0.2**.
3. `lite` ships with a mail catcher optional container documented (not required for install); reachable through the BB-R-007.12 allow-list.

Exit test: invite from BB-R-005 lands in the mail catcher with a working set-password link.

## BB-R-011 Packaging & install
Medplum ref: `self-hosting/` (index, running-full-medplum-stack-in-docker, install-from-scratch, install-on-kubernetes, server-config, setting-configuration, super-admin-guide, super-admin-cli, upgrading-server, disaster-recovery, monitoring, opentelemetry); inventory area 4.1 (Table A)
Fill: **glue** (Helm chart, compose, profiles, docs) · Version: v0.1 · Status: blessed

1. `docker compose up` with the `lite` file starts Postgres, Keycloak, Big Book (HAPI embedded); ≤10 min on a laptop with a cold image cache; two commands max (mirrors Medplum's `curl … && docker compose up -d`). Admin UI reachable with the `admin` overlay (ADR-004; documented +3 min, outside the `lite` timer). **Minimum viable `lite` config is 5–6 environment variables and no config file**, three of them generated at first boot (area 4 note b).
2. `helm install bigbook` with `profile: lite` produces the same stack on any Kubernetes; `profile: full` adds OpenSearch, MinIO, n8n, Traefik, Grafana stack, Vault, Snowstorm (per version). Scale-out (HTTP-only vs worker JVMs) is a Spring profile in `full`, not a config key (D41).
3. Configuration by env/values only (D32): Spring Boot's native property-source order gives env-over-file for free — no loader code (Medplum's five loaders are positional, and its own docs claiming "env wins" are false, T38). AWS SSM / GCP / Azure loaders **dropped** for v0.x; Vault via Spring Cloud Vault in `full`. Every key documented in one table (`docs/guides/config.md`), with defaults, **generated from the `@ConfigurationProperties` classes with a test that fails when a property has no doc row** (T40). Secrets never in values files (see BB-R-005.6 for what this does and does not promise at rest).
4. First-boot bootstrap: realm, super-admin project, super-admin user (fail-closed password, D34), default policies, the `medplum-cli` public client (BB-R-017 v0.2); idempotent on restart.
5. Health endpoints for all components; `docker compose ps` shows healthy within the 10 minutes.
6. Pinned upstream versions in one place; upgrade guide per release.
7. Backup/DR, load testing, cloud-specific guides — **defer v0.3** (docs). Medplum's super-admin database operations (`reindex`, `purge`, migrations, vacuum, index rebuild, table settings, schema drift, `$db-*`, `$explain`) are **not ported**; database work happens through Postgres tooling, Flyway and the Grafana stack, with `psql` runbooks here (D8, T34). `reindex` → HAPI `$reindex`; `purge` → HAPI `$expunge` (BB-R-018, D22).
8. No Redis in `lite` and no rate limiter in v0.1 (D33, D36): every `redis.*` and `rateLimit*` key is dropped; BB-R-023 (v0.2) is Traefik. Medplum's `enabledSearchParameters` allow-list and `preCommitSubscriptionsEnabled` are dropped as keys — the HAPI data-level and interceptor equivalents exist natively (D39, D40). CORS: echo the request origin and set `Access-Control-Allow-Credentials: true`, never `*` (D43 — the SDK sends credentials on every call; Medplum ships `allowedOrigins: "*"`).

Exit test: fresh Ubuntu VM, `curl` + `docker compose up -d` with a `.env` of ≤6 lines, timer < 10 min, Baymax smoke script passes; `docs/guides/config.md` test passes.

## BB-R-012 Java client SDK
Medplum ref: `sdk/core` (MedplumClient), `api/`; `fhir-datastore/working-with-fhir`; inventory area 5.1
Fill: **glue** (thin layer over HAPI generic client + Keycloak) · Version: v0.1 · Status: blessed

1. `BigBookClient` with auth: client credentials, password (**Keycloak ROPC, dev only — not a Medplum-compatible flow**; Medplum's `startLogin` is the non-goal `Login` protocol, T42), authorization code + PKCE (desktop/mobile), token refresh, `refreshProfile()`/`getProfileAsync()` = `GET /auth/me`, `getProfile()` returns the cached session (T43).
2. Typed CRUD on HAPI R4 structures: `create/read/vread/update/patch/delete/upsert(search)`, `createIfNoneExist`.
2a. Raw HTTP escape hatch: `get/post/put/patch/delete` + `fhirUrl(...)` (D52 — every SDK needs one; v0.1).
3. `search(Class, params)` returning a page with `next()` (requires the server to emit an absolute `next` link and accept `_count=1000`, T45); `searchOne`; `searchResources` (unwrapped — note `bundleToResourceArray` returns included resources mixed in; preserve `search.mode` and match-before-include ordering); `_include` convenience helper is a Big Book addition, not a port (T44).
4. `executeBatch/Transaction`; `graphql(query)`; `validate` (posts a bare resource, as Medplum does — T46).
5. Binary: `createBinary(InputStream, contentType, filename)`, `download(url)`.
6. Admin helpers: `inviteUser` (the one `@medplum/core` types), `createClient` and `createProject` (super-admin) — both **BB-only**, no `MedplumClient` equivalent (T47).
7. Spring Boot starter: `bigbook.url/client-id/secret` → autoconfigured bean.
8. Autobatching — **non-goal**: client-side read coalescing for React render storms, opt-in in Medplum (`autoBatchTime` default 0); the JVM answer is parallel calls or an explicit batch (T48). No server behaviour depends on it.
9. `startAsyncRequest`/`bulkExport` — **defer v0.2** (with BB-R-020; D52).
10. `rateLimitStatus` — **defer v0.2** (with BB-R-023; D52).
11. `patientEverything` — **defer v0.2** (BB-R-001.10; D52).
12. `executeBot` — **defer v0.2** (with BB-R-016; D52).
13. `subscribeToCriteria` (WebSocket) — **defer v0.2** (with BB-R-025; D52).

Exit test: Baymax's patient read/write path runs on the SDK alone — no raw HTTP (the escape hatch exists but is unused by Baymax).

## BB-R-013 Admin UI (low-code)
Medplum ref: `app/` (index, app-introduction, sign-in-page, admin-page, apps-tab, invite)
Fill: **wire Appsmith CE** on `/fhir/R4` + `/admin`, `admin` compose overlay, app JSON in `app/lowcode/` (ADR-004, decided 2026-09-12, amended 2026-09-17) · Version: v0.1 (low-code) → v0.3 (Vaadin Flow in the Big Book server; issue #1 closed the `@medplum/app` path) · Status: blessed

1. v0.1: sign-in with Appsmith accounts; Big Book calls via a super-admin `ClientApplication`; project switcher = `GET /admin/projects` (**BB-only** route, BB-R-005.12) + optional `X-Project` header (BB-R-005.11). Per-user Keycloak OIDC and human attribution (`X-Medplum-On-Behalf-Of`) — **defer v0.2** (BB-R-024). Screen inventory per ADR-004 is the build list. If Appsmith calls Big Book from the browser, BB-R-011.8's CORS-with-credentials rule applies (D43).
2. Resource browser: list any type with search bar, detail view, JSON editor (create/update), history tab, delete.
3. Admin page: project details/settings, Users (list, invite, edit membership/policy/admin), Patients, Clients (create; secret **retrievable** on the client's detail page — D5), Secrets (values shown to project admins, plaintext at rest in `lite` — BB-R-005.6), Bots (**v0.2**), Sites (**non-goal**).
4. AccessPolicy editor (JSON with schema validation, rejecting non-evaluable criteria per BB-R-006.1) and assignment.
5. Subscription list with last-delivery status (reads the per-attempt AuditEvents, BB-R-007.3).
6. Timeline/patient chart views, Apps tab, questionnaire builder — **non-goal v0.x**; that's Medplum React territory (ADR-004 / v1).

Exit test: without touching the API, an admin creates a project, invites a user with a policy, creates a client and reads its secret back, and edits a Patient's JSON.

## BB-R-014 Wire-compatibility surface
Medplum ref: `api/` (fhir/overview, oauth/*, auth/*, project-admin/*); Medplum custom resource types (`api/fhir/medplum/*`); inventory areas 1–3, 5 (SDK-grade checklist, 31 items)
Fill: **glue**, scope fixed by **ADR-003** (decided 2026-09-12, amended 2026-09-17: option B v0.1, SDK-grade C v0.2 with the supported subset defined, app-grade C v1.0 scoped to the project-user surface) · Version: v0.1 / v0.2 / v1.0 per sub-item · Status: blessed

1. FHIR base `/fhir/R4` with Medplum-identical paths; `Content-Type: application/fhir+json` on FHIR resources (Medplum returns `application/json` from `$graphql`, `/metadata`, `$versions`, the well-knowns and the bulk manifest — T28); status codes **404/410/412/400** semantics — 422 is effectively unreachable in Medplum (only `business-rule` maps to it; validation errors carry no `id` and land on 400 — T28). Method-not-allowed is 404 in Medplum, 405 in HAPI.
2. OAuth paths `/oauth2/authorize|token|userinfo|logout` and `/auth/me` as in BB-R-004.8.
3. Medplum admin resource types readable/writable in Medplum JSON shape: `Project`, `ProjectMembership`, `AccessPolicy`, `ClientApplication`, `User` (read-only projection of Keycloak — sufficient for SDK-grade; app-grade needs `User` writes, `$rescope`, `$update-user-email`, T13), `UserConfiguration` — **v0.2**. `Bot` v0.2, `Login` non-goal. Also **v0.2** (D4): `X-Medplum: extended` honoured on every response (`meta.project`, `meta.author`, `meta.compartment`; stripped without the header — Medplum blanks six `meta` fields otherwise); `/auth/me` returning `config` with a non-empty `menu` and a compiled `accessPolicy`; `ClientApplication.id` = OAuth `client_id`; `AsyncJob`-shaped body at `Content-Location` for `Prefer: respond-async`; the `OperationOutcome.id` slug set including `multiple-matches` (412) and `business-rule` (422) (D46). Byte-identical 304 on a no-op PUT (D12); `_offset` on `_history` (D45).
4. Admin routes payload-compatible — **v0.2**, using Medplum's **real** spellings (D2, replacing the invented list): `POST /admin/projects/:id/invite` (200 + `ProjectMembership`; the only one `@medplum/core` types — byte-exact), `POST …/client` (201), `POST …/bot` (201), `POST …/settings|secrets|sites` (whole-array replace, 200 + `Project`), `GET …/:projectId` (`{project:{id,name,setting,secret,site}}`), `GET/POST/DELETE …/members/:mid` (POST full resource, 403 on shape error), `POST …/members/:mid/mfa/reset|resetpassword`, `POST /admin/projects/setpassword`. There is no `PATCH …/members/:mid` (docs are wrong — T17) and no `GET /admin/projects` in Medplum. Big Book's four BB-only routes (BB-R-005.12) are not part of this surface.
5. Target per version: v0.2 = `@medplum/core` runs unmodified (SDK-grade), where "unmodified" means the **supported method subset** in inventory area 5 Table A (D50, T49); out of scope: the `Login`-protocol auth methods, FHIRcast, CDS Hooks, key-value, schema introspection (`requestSchema`, `$expand-profile`), Agent push, PDF helpers. v1.0 = `@medplum/app` runs unmodified on the **project-user surface** — sign-in, resource browse/edit, project admin pages; the super-admin database/migration console is out of scope by design, since twelve of its operations are Postgres catalog and `ALTER` statements over HTTP (D24). Gated on the `Login` sign-in flow. Resolved by ADR-003.
6. Status/header alignment with Medplum: **v0.1** by HAPI config plus zero-cost settings (UUID ids, no update-as-create, `_count` cap 1000 inclusive, `application/fhir+json`, `ETag`, CORS with credentials — D43); **v0.2 glue** (T29 adds eight): OperationOutcome `id`/`issue.code` mapping incl. the two new slugs, 409→412 on `If-Match`, 422→400 on validation (keep 422 for `business-rule`), `X-Trace-Id`, `Location` without the `/_history/<vid>` suffix, batch entry `response.status` bare `"201"` and relative `response.location`, DELETE body `OperationOutcome{id:'ok'}`, method-not-allowed 404, the `Access-Control-Expose-Headers` list (`Content-Location, ETag, Last-Modified, Location, X-Request-Id, X-Trace-Id`), `$graphql` returning `application/json`, terminology-unknown-url 400 (T36). Never emit a malformed `RateLimit` header — the SDK throws on it; emit none in v0.2 (D47).

Exit test (v0.1): Medplum's own `curl` examples from `fhir-datastore/creating-data` and `api/oauth/token` work against Big Book unchanged; `GET /.well-known/openid-configuration` advertises Big Book URLs and a token from `/oauth2/token` validates against `/.well-known/jwks.json`; a browser page on another origin can call `/fhir/R4/metadata` with credentials.
Exit test (v0.2, replaces the smoke test — D49): an unmodified `@medplum/core` script against Big Book must, in one run: (1) complete `startClientLogin`; (2) have `getProfile()`, `getProject()`, `getProjectMembership()` and `getAccessPolicy()` all non-undefined after login; (3) `createResource` a Patient and get the body back with a server-assigned id; (4) `createResourceIfNoneExist` the same Patient and get the **existing** one; (5) `upsertResource` it by identifier; (6) `readResource` it, `deleteResource` it, `readResource` again and assert the thrown `OperationOutcomeError` satisfies `isGone`; (7) read a random UUID and assert `isNotFound`; (8) create 1001 Patients and assert `searchResourcePages` yields two pages; (9) `readHistory` with `{offset:1}` and assert a different page; (10) run the whole script from a browser against a different origin.

## BB-R-015 Observability (minimum)
Medplum ref: `self-hosting/monitoring`, `self-hosting/opentelemetry`
Fill: **wire OpenTelemetry** (exporters on in `full`; `lite` logs to stdout) · Version: v0.1 minimum, v0.2 full stack · Status: blessed

1. Structured JSON logs with request id, project id, user id on every request; every policy denial and every system-context elevation (BB-R-005.7) is a log line.
2. `/actuator/health`, `/actuator/metrics`; OTel traces off by default in `lite`.
3. Medplum error bodies carry a `tracing` extension with `requestId`/`traceId`; whether Big Book adds it is a v0.2 decision with `X-Trace-Id` (BB-R-014.6).

Exit test: one `curl` to `/fhir/R4/Patient` produces one log line carrying project + user.

## BB-R-029 Non-functional requirements (`lite`)
Medplum ref: none (Medplum publishes no NFRs; `self-hosting/monitoring` only); `docs/HOW-IT-WORKS.md` NFR table
Fill: **wire** (HAPI, Postgres, JVM defaults) + one k6 script and a seeded dataset (glue, test-only, uncounted) · Version: v0.1 · Status: blessed (2026-09-18)

Targets are for the `lite` profile on a laptop-class host (2 vCPU, 8 GB). Measured against a seeded dataset of 100k `Patient` and 1M `Observation` in one project.

1. Install: ≤ 10 min cold-cache, three containers (restates BB-R-011.1).
2. Footprint: `lite` idles under 3 GB RAM total across the three containers after 5 min idle.
3. Read latency: `GET /fhir/R4/Patient/{id}` p95 < 100 ms.
4. Search latency: single-parameter search (`Patient?name=`, `Observation?subject=`) p95 < 300 ms; `_count` ≤ 20.
5. Write latency: single `POST /fhir/R4/Observation` p95 < 200 ms with referential integrity on.
6. Concurrency: 50 concurrent SDK clients running the read/search/write mix for 5 min with zero 5xx.
7. Subscription delivery: matched → rest-hook POST sent, p95 < 5 s (restates BB-R-007 exit test).
8. Restart safety: `docker compose restart` mid-load loses no committed write and no pending delivery (restates BB-R-007.3).
9. Data safety: Postgres is the only state; `pg_dump` + `pg_restore` produces an identical `_history` for every resource.
10. Auth: an invalid or expired bearer is rejected with 401 without a database hit; a valid bearer costs at most one membership lookup per request (cached per BB-R-006 rule cache).
11. Logs: every request emits one JSON line with request id, project id, user id (restates BB-R-015).

Not v0.1: horizontal scaling, HA Postgres, multi-node subscriptions (ADR-002/006), rate limits (BB-R-023), BALP audit (BB-R-018), backup/DR guides (v0.3).

Exit test: `perf/k6/lite.js` against the seeded stack in CI reports p95 for (3)–(5) under target and zero 5xx for (6); a restart step in the same job proves (8); `docker stats` sample proves (2).

---

# Deliberate divergences from Medplum (contract, not defects)

Each is a row in `docs/guides/medplum-parity.md`. Big Book does the correct thing; Medplum's behaviour is recorded so nobody "fixes" Big Book back.

| # | Where | Medplum | Big Book |
|---|---|---|---|
| D11 | BB-R-001.4 | Transactions atomic only behind `transaction-bundles`; otherwise silently a batch; 50/8 caps | Always atomic, no gate, no caps |
| D12 | BB-R-001.1 | No-op PUT returns existing resource, 304 | v0.1 versions unconditionally; 304 glue v0.2 |
| D14 | BB-R-006.1 | Malformed criteria drops all remaining policy restrictions (fail open) | Fail closed |
| D15 | BB-R-006.1, BB-R-007.1 | Criteria the in-memory matcher cannot evaluate silently yield `false` (`:not-in` inverts) | Rejected at write time with 400 |
| D16 | BB-R-001.6 | `checkReferencesOnWrite` off by default | On by default |
| D17 | BB-R-002.7 | Page links re-serialised, `_summary` dropped; structured cursor | Params echoed; cursor not wire-compatible |
| D18 | BB-R-003.4 | Depth/cost limits advisory (`reportError` commented out) | Enforcing |
| D19 | BB-R-002.1 | Quantity search ignores system/code; number search has no precision | Correct (HAPI) — fewer results |
| D34 | BB-R-005.7 | Silent fallback super-admin password `medplum_admin`; registration on | Fail-closed generated password; registration off |
| D57 | BB-R-007.10 | Rest-hook policy check is a no-op | Enforced |
| T30 | BB-R-005.7 | `getSystemRepo()` callable anywhere, unaudited | Internal, logged |
| T18/T20/T21/T23/T24/T27 | BB-R-001–003 | No type-level history, instance `$validate`, instance `$graphql`, `:above/:below/:in`, `_include=*`, runtime `SearchParameter` | HAPI supersets kept |

# Verify on the pinned HAPI container

Verify-first blocks on the implementing issues. A "no" on V1 stops the work and is raised; the rest change fills or exit tests.

| # | Question | Issue | Blocking |
|---|---|---|---|
| V1 | `SearchNarrowingInterceptor` fires for `_include`/`_revinclude` sub-queries; `GraphQLProvider` resolves through the DAO/interceptor chain — **answered 2026-09-18 (issue #9): no / storage pointcuts only; ADR-001 amended.** Follow-ups (a) `PRESEARCH_REGISTERED` for GraphQL nested searches, (b) `PREACCESS` drop on single read → 404 | #7, #9, #10 | **yes** — cross-tenant leak otherwise |
| V2 | `_filter` operators and dotted paths on HAPI | #9 | no |
| V3 | GraphQL connections and mutations | #10 | no |
| V4 | Runtime custom `SearchParameter` + `$reindex` | #9 | no |
| V5 | `InMemoryResourceMatcher` capability set | #7, #12 | no |
| V6 | `_count` cap, default `_total`, page-link shape | #9 | no |
| V7 | `/metadata` operation audit on a fresh `lite` | #8 | no |
| V8 | `$expand` on base R4 ValueSets; terminology content upload story | #8 | no — decides BB-R-001.11's version |
| V9 | `SUBSETTED` tagging on `_summary`/`_elements` (D44) | #9 | no |

---

# v0.2 — placeholders (specified after v0.1 is blessed)

| ID | Brick | Fill | Medplum ref |
|---|---|---|---|
| BB-R-016 | Bot SDK (Camel, `@Bot` on a route). Triggers in v0.2: subscription, `Cron` resource (adopted; `Bot.cronString` dropped — D59), `$execute`. Public webhook v0.3; CDS Hooks and custom-operation dispatch non-goal; pre-commit hooks non-goal/v0.3 (D25, D27, D63). `runAsUser` only on `$execute`, never on the subscription trigger (D58). `$deploy`/`$init` are CLI registration, not code upload. Subscription-triggered bots receive the rest-hook headers as input (T61). Timeout default 10 s; secrets merged bot-project → runAs-project, later wins, `systemSecret` only for `Bot.system` (T62). | glue | `bots/` |
| BB-R-017 | CLI (login, CRUD, bulk import, bot deploy). `medplum` CLI compat: bootstrap the `medplum-cli` public client (redirect `http://localhost:9615`) and a Basic-auth→cached-token filter (~80 lines), since the CLI sends HTTP Basic client credentials on every FHIR call (D10). | glue picocli | `cli/` |
| BB-R-018 | Audit events (BALP on every access; `redactAuditEvents`); `$expunge` / hard delete as `wire HAPI $expunge` — the only way to remove history (D22) | wire HAPI | `compliance/` |
| BB-R-019 | Consent enforcement | wire HAPI ConsentInterceptor | `consent/` |
| BB-R-020 | Bulk export `$export` — wire HAPI **plus glue** (~200 lines) for the Medplum poll/manifest URL (`/fhir/R4/bulkdata/export/:id`, `/fhir/R4/job/:id/status`) and the `AsyncJob` resource; `$cancel`. Exit test: `MedplumClient.bulkExport()` completes end-to-end (D28). Async batch (BB-R-001.7) rides the same machinery (D62). | wire HAPI + glue | `api/fhir/operations` |
| BB-R-021 | Full-text search (`full`) — a new capability, not parity (T22) | wire OpenSearch | `search/` |
| BB-R-022 | SMART App Launch scopes + launch context (`SmartAppLaunch`, `launch`/`aud` params, `fhirUser` claim) | wire Keycloak ext (ADR-005) | `integration/smart-app-launch`, `access/smart-scopes` |
| BB-R-023 | Rate limits per project/user — Traefik for HTTP categories (login/auth/default). Medplum's cost-weighted FHIR quota (`RateLimit: "fhirInteractions"`) and resource caps are **non-goal** (D21, D36). Emit no `RateLimit` header (D47). | wire Traefik | `rate-limits` |
| BB-R-024 | On-Behalf-Of (incl. `X-Medplum-On-Behalf-Of` for admin clients), third-party issuers accepted directly (T10), open patient registration, project linking, `/auth/revoke`, `/auth/preauthorize` (T11), admin-initiated credential routes (D6), project-wide `mfaRequired` (D7), external-to-internal token exchange (D9: Keycloak preview flag in `full`; ~150 lines glue is the expected end state) | wire Keycloak / HAPI | see v0.1 deferrals |
| BB-R-025 | Subscription extras: WebSocket (`lite`-only single-node — in-JVM session map + Spring `WebSocketHandler`, `$get-ws-binding-token` JWT mint is glue; multi-replica `full` needs sticky sessions or a broker, the one place ADR-002 could be revisited — D29, D61), expression criteria (T59), auto-disable (D60), server-scoped | wire/glue | `subscriptions/` |
| BB-R-026 | Per-project SMTP + branded emails | wire Spring Mail | `user-management/custom-emails` |
| BB-R-027 | Presigned URLs + binary security context (MinIO); `X-Security-Context` (T51) | wire MinIO | `self-hosting/presigned-urls` |
| BB-R-014.3/4/6 | SDK-grade Medplum compat: custom resource types, `X-Medplum: extended` meta fields, `/auth/me` shape, admin route payloads (real spellings), status/header glue incl. the eight T29 items and the two D46 slugs, 304 on no-op PUT (D12), `_offset` on history (D45), token response re-shape (D3/D51), CapabilityStatement customisation (overlay, include/exclude types, per-type interactions — HAPI generates it from providers; D38) | glue | `api/`, ADR-003 |
| BB-R-001.10/11 | `Patient/$everything` (D30); terminology `$lookup`, `$validate-code` ×2 (D23); `$expand` here only if V8 fails | wire HAPI | `api/fhir/operations` |
| BB-R-003.4 | Per-project introspection toggle (D18) | glue | `graphql/` |
| BB-R-012.9–13 | SDK: `startAsyncRequest`/`bulkExport`, `rateLimitStatus`, `patientEverything`, `executeBot`, `subscribeToCriteria` (D52) | glue | `sdk/core` |

# v0.3 — placeholders

- **BB-R-028 HL7v2 agent** (`agent/`, `integration/hl7-interfacing`; T15, T35): the `Agent` resource, `Agent/$push` (the one SDK-grade operation, `pushToAgent`), `$status`, `$bulk-status`, `$reload-config`, plus a websocket control channel. `$stats` and `$fetch-logs` are absorbed by BB-R-015's OpenTelemetry path, not reimplemented as operations; `$upgrade` is a packaging decision under BB-R-011.
- Terminology with **Snowstorm for SNOMED CT only** (D23); `$subsumes`, `$translate`.
- BD IG v1; proper admin UI (Vaadin Flow, ADR-004); migration guide + CLI import (`migration/`); DR/backup/cloud guides.
- Public bot webhook `POST /webhook/:id` (D25); pre-commit bot hooks as a candidate, not a promise (D63); external attachment auto-download (D62).

# v1.0

App-grade Medplum compat (`@medplum/app` unmodified on the **project-user surface**, D24) — blocked on emulating the `Login` sign-in flow. Confirmed 2026-09-12 (issue #1): `@medplum/app` has no config path to `signInWithRedirect` or a direct OIDC redirect; even the `DomainConfiguration` external-IdP route runs through the `Login` protocol. App-grade additionally needs: `User` writes and `User/$rescope`, `$update-user-email` (T13); GraphQL `StructureDefinitionList`/`SearchParameterList` connections and `StructureDefinition/$expand-profile` for `requestSchema` (T53); `/auth/me.security` sessions and `/auth/revoke`. The super-admin database console is out of scope by design. Stays v1.0; see `docs/guides/medplum-parity.md`.

# Non-goals (v0.x)

Medplum Provider app; clinical workflow features (intake, charting, scheduling incl. `Appointment/$find|$book|$hold|$confirm|$cancel`, orders, meds, care plans, comms, billing incl. `Claim/$submit|$export`, `CoverageEligibilityRequest/$submit`, questionnaire builder, protocols, provider directory); US integrations (DoseSpot, ScriptSure, Health Gorilla, Stedi, Candid, eFax, Twilio); DICOM (incl. `DicomStudy/Series/Instance`), FHIRcast, CDS Hooks (incl. `Bot.cdsService`), C-CDA, ePA, Analytics/Athena/data-warehouse sync, AI features (`$ai`, `ai`/`ai-realtime` features, `$extract`); **SMART Health Cards / Links** (T34); React component library (revisit at v1 per ADR-003/004); SCIM; mTLS; patient dedup pipeline and `$match`; SDK autobatching; Medplum marketplace (`Package`, `PackageRelease`, `PackageInstallation`, `$install`) and `Enterprise`; MCP surfaces (`/.well-known/oauth-protected-resource`, `/oauth2/register`, `api-catalog`); `Project.site`, `ClientApplication.signInForm`, `strictMode`, project-scoped issuers (T16); the `$db-*`/`$explain` super-admin operations and Medplum-schema migrations (D8); FHIR quota and resource caps (D21); the custom-operation-via-Bot mechanism (D27); email-OTP MFA (D7); `sw`/`:present` search extensions (T23); the 16 dropped operations (`$summary`, `$match`, `$evaluate-measure`, `PlanDefinition/$apply`, `ChargeItemDefinition/$apply`, `Appointment/$cancel`, `$csv`, `$set-accounts`, `$refresh-reference-display`, `$expand-profile`, `$rebuild-base-definitions`, `Project/$clone`, `Project/$rate-limits`, `$graph`, `CodeSystem/$import`, `ConceptMap/$import` — D31); Medplum's Dispatch, Reindex, SetAccounts, PostDeployMigration, LambdaCleaner and DataWarehouseSync queues (D62); pre-commit bot subscriptions in v0.1/v0.2 (D63).
