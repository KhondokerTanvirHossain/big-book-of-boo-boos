# Big Book — Requirements

> What Big Book does, brick by brick, mapped to Medplum. Says *what* is observable and *which component* fills it — never *how*. How lives in `docs/adr/` and code.
> Lives at `docs/REQUIREMENTS.md`. PO drafts; Claude Code commits and keeps it in sync with ADRs. If code and this file disagree, code wins — then fix this file.
> Medplum reference = `https://www.medplum.com/docs/<path>`; source `packages/docs/docs/<path>` in medplum/medplum. Snapshot: Sept 2026.

## Conventions

- **Fill**: `wire` (upstream component already does it — expose + configure), `glue` (Big Book code, counts against the 5–10k budget), `defer` (named version), `non-goal` (stated, with reason).
- **Status**: `draft` → `blessed` → `done`.
- Every requirement has an exit test a machine or a human can run in <5 minutes.
- Sub-requirements carry their own fill/version; a brick is done when all its v0.x sub-requirements for that version are done.

---

# v0.1 — `lite` profile

**Exit criterion (from roadmap):** Baymax reads and writes patient records through Big Book using the Java SDK, on a `lite` stack that installed in ≤10 minutes on a laptop.

`lite` = Postgres + HAPI FHIR JPA + Keycloak + Big Book server. Nothing else. Every v0.1 requirement must be satisfiable inside that set.

---

## BB-R-001 FHIR datastore
Medplum ref: `fhir-datastore/` (creating-data, reading-data, updating-data, deleting-data, resource-history, fhir-batch-requests, profiles, working-with-fhir)
Fill: **wire HAPI JPA** · Version: v0.1 · Displaces: nothing · Status: blessed

What a client can do at `/fhir/R4`:
1. `create` (POST), `read` (GET /Type/id), `vread`, `update` (PUT — new version every time), `patch` (JSON Patch), `delete` (soft; 410 Gone afterwards, 404 for never-existed).
2. `upsert` = conditional update `PUT /Type?search` — one match updates, zero creates, many → error.
3. `_history` per resource and per type; every version retained.
4. Batch (`type: batch`) and transaction (`type: transaction`) bundles, with internal (`urn:uuid`) and conditional references, `ifMatch` version checks, PATCH entries.
5. `$validate` against base R4 and any loaded profile; `meta.profile` honoured; `Project.defaultProfile` (see BB-R-005) auto-applied when absent.
6. Reference integrity on write (Medplum `checkReferencesOnWrite`) switchable per project.
9. Server-assigned UUID ids only; `PUT /Type/<new-id>` (update-as-create) is rejected as in Medplum (ADR-003): 404 for project users; super-admin may set ids (BB-R-005.7) and gets 200. Observed values in `docs/guides/medplum-parity.md`.
7. Async batch bundles (`Prefer: respond-async` + job status) — **defer v0.2**.
8. Patient `$match` / dedup pipeline — **non-goal** (workflow guide, not platform).

Exit test: transaction bundle creating Patient + Observation with a `urn:uuid` reference returns 200; `GET Patient/id/_history` shows 2 versions after a PUT; deleted Patient returns 410.

## BB-R-002 Search
Medplum ref: `search/` (basic-search, chained-search, includes, advanced-search-parameters, filter-search-parameter, paginated-search)
Fill: **wire HAPI** · Version: v0.1 · Displaces: nothing · Status: blessed

1. All R4 search parameters per resource type; string/token/reference/date/quantity semantics; `:not`, `:missing`, `:exact`, `:contains`.
2. Forward and reverse chaining (`subject.name`, `_has:`), nested.
3. `_include`, `_revinclude`, `:iterate`.
4. `_id`, `_lastUpdated`, `_summary`, `_elements`, `_tag`, `_profile`, `_security`, `_source`, `_total`, `_sort`, `_count`.
5. `_filter` — wire HAPI (`filter_search` enabled).
6. `_compartment=Project/<id>` — **glue**: maps to the tenant partition (see BB-R-005).
7. Paging: offset (`_offset`) and cursor (`next` links) — HAPI paging cache; document the difference from Medplum's cursor.
8. Full-text `_text`/`_content` — **defer v0.2** (`full` profile, OpenSearch).

Exit test: `GET /Observation?subject.name=Simpson&_include=Observation:subject&_sort=-date&_count=10` returns a page with `next` link; `_compartment` filters to one tenant only.

## BB-R-003 GraphQL
Medplum ref: `graphql/` (index, connections, mutations)
Fill: **wire HAPI GraphQL** · Version: v0.1 · Status: blessed

1. `POST /fhir/R4/$graphql` and `/fhir/R4/Type/id/$graphql`, FHIR GraphQL spec; nested references, list filtering, `_reference` reverse lookup.
2. Connection-style queries (`PatientConnection { count offset edges }`) — measure HAPI gap; if absent, **defer v0.2**, not glue.
3. Mutations (`PatientCreate`, `PatientUpdate`) — same rule as (2).
4. Introspection togglable per project (`graphql-introspection` feature); depth/search-count limits configurable.
5. Access policies (BB-R-006) apply at field level — same enforcement path as REST.

Exit test: GraphQL query for a Patient with nested `ObservationList(_reference: subject)` returns data, and hidden elements from the caller's policy are absent.

## BB-R-004 Authentication
Medplum ref: `auth/` (index, medplum-as-idp, external-identity-providers, domain-level-identity-providers, client-credentials, client-assertion, token-exchange, mfa, how-mfa-works, logout, session-management, pre-authorized-code, on-behalf-of, direct-external-auth, mtls); `api/oauth/*`; `api/auth/*`
Fill: **wire Keycloak** (+ glue for Medplum-shaped endpoints, scope per ADR-003) · Version: v0.1 · Status: blessed

1. Big Book as IdP: email/password login, registration, password reset/set, email verification — Keycloak realm login flows.
2. OAuth2/OIDC: authorization code + PKCE, refresh, client credentials, client assertion (private_key_jwt), token exchange, logout, session listing/revocation — Keycloak.
3. MFA (TOTP) per user; project can require MFA at invite (`mfaRequired`) — Keycloak required action, set by BB-R-005 invite.
4. External IdP (Google, Auth0, Cognito, any OIDC/SAML) — Keycloak identity brokering. Domain-level IdP (user@corp.com → corp IdP) — Keycloak organisation domains.
5. Direct external auth (accept a third-party token without exchange) — **defer v0.2**.
6. On-Behalf-Of — **defer v0.2** (Keycloak token exchange with `requested_subject`).
7. mTLS client auth — **non-goal v0.x**.
8. Medplum-shaped endpoints (ADR-003, option B): `/oauth2/authorize|token|userinfo` and `/.well-known/jwks.json` proxied to Keycloak unchanged; `/.well-known/openid-configuration` and `/oauth2/logout` re-shaped by Big Book; `/auth/me` **glue** (returns profile, project, membership, access policy, user config — required by the SDK). Token issuer = Big Book public URL.
8a. Medplum `Login` resource and `/auth/login|newuser|newproject|newpatient|profile|scope|mfa/*|changepassword|resetpassword|setpassword|verifyemail` — **non-goal v0.x**; Keycloak flows replace them.
9. Bearer token on every `/fhir/R4` and `/admin` call; token carries `project`, `profile`, `membership` claims (mapper).

Exit test: client credentials flow returns a token that reads `/auth/me` with the correct project + profile; a user invited with `mfaRequired` cannot complete login without TOTP.

## BB-R-005 Tenancy — Project, User, ProjectMembership, invitations
Medplum ref: `access/projects`, `user-management/` (index, project-vs-server-scoped-users, external-ids, open-patient-registration), `api/project-admin/invite`, `api/project-admin/client`, `self-hosting/project-settings`, `self-hosting/super-admin-guide`
Fill: **glue** (tenant model) on Keycloak organisations + HAPI partitions · Version: v0.1 · Displaces: nothing (HAPI has partitions, no project/membership model) · Status: blessed

1. **Project** = isolated container: resources in one project never reference another; each project has its own users, settings, secrets. One project ↔ one Keycloak organisation ↔ one HAPI partition. `resource.meta.project` populated on every resource.
2. **User** identity has scope `server` (devs/admins, many projects) or `project` (clinicians/patients, one project). Practitioner defaults `server`, Patient defaults `project`.
3. **ProjectMembership** links User → Project with `profile` (Practitioner | Patient | RelatedPerson | ClientApplication | Bot), `admin` flag, `accessPolicy` list, `userConfiguration`. Same user, different privileges per project.
4. **Invite**: `POST /admin/projects/:id/invite` with resourceType, name, email, `scope`, `admin`, `accessPolicy`, `mfaRequired`, `sendEmail`; creates profile resource + membership + Keycloak user (or links existing) and emails (BB-R-010).
5. **ClientApplication**: `POST /admin/projects/:id/client` creates a machine identity (Keycloak confidential client) with membership + policy. External IDs on identities (`external-ids`) — v0.1 as identifier on the profile resource.
5a. Routes in (4)–(5) exist in v0.1 with Big Book payloads; byte-compatibility with Medplum's request/response JSON — **defer v0.2** (BB-R-014.3/4).
6. **Project settings** (v0.1 subset): `checkReferencesOnWrite`, `defaultProfile`, `features[]`, `defaultPatientAccessPolicy`, `setting[]`/`secret[]` (secrets in Keycloak client attrs or env for `lite`; Vault in `full`), `rateLimit` (→ BB-R-023, v0.2).
7. **Super-admin project**: server-wide view; only role that can create projects, override `id`/`meta`, read protected resources. Bootstrapped from env at first start (`BIGBOOK_ADMIN_EMAIL`/`_PASSWORD`). Super-admin tokens select the target partition with an `X-Project` header (ADR-004; ~30 glue lines).
8. **Project linking** (shared read-only reference projects, `exportedResourceType`) — **defer v0.2** (HAPI multi-partition read).
9. **Open patient registration** — **defer v0.2** (Keycloak self-registration + `defaultPatientAccessPolicy`).
10. SCIM (`api/scim`) — **non-goal v0.x** (Keycloak has SCIM extensions; wire in v1 if asked).

Exit test: super-admin creates two projects; invites the same email to both, admin in A and read-only in B; Patient created in A is invisible from B's token; `/auth/me` under each token shows the right membership.

## BB-R-006 Access policies
Medplum ref: `access/` (index, access-policies, admin, multi-tenant-access-policy, ip-access-rules, user-configuration, binary-security-context, tenant-selector, smart-scopes)
Fill: **glue** (ADR-001, decided 2026-09-12: no external engine — Medplum-shaped `AccessPolicy` resources translated at request time into HAPI `AuthorizationInterceptor` rules + `SearchNarrowingInterceptor`; field hiding, readonly restore, parameter substitution and subscription-side enforcement as Big Book interceptor hooks; ≈1.1–1.3k lines) · Version: v0.1 · Displaces: nothing · Status: blessed

1. `AccessPolicy` resource with `resource[]` entries: `resourceType` (or `*`), `criteria` (search string; `:not`/`:missing` only, no chaining), `interaction[]` (create/read/update/delete/search/history/vread), `readonly`, `readonlyFields[]`, `hiddenFields[]`, `compartment`.
2. Policies attach via `ProjectMembership.accessPolicy[]`; multiple policies OR-combine. `admin: true` bypasses.
3. Parameterized policies (`%patient`, `%profile`, `%requestor` + custom `access[].parameter`) — resolved per membership.
4. Default policies per project (Practitioner default, Admin default) with the promote/demote swap.
5. `AccessPolicy.basedOn` for template linkage — v0.1 as data only; no behaviour.
6. `UserConfiguration` (menu/search shortcuts for the app) — stored, served by `/auth/me`; UI use in BB-R-013.
7. `writeConstraint` (FHIRPath pre/post conditions) — **defer v0.2**.
8. IP access rules — **defer v0.2** (Traefik middleware or Keycloak condition).
9. Binary security context — **defer v0.2** (with BB-R-009 MinIO).
10. SMART scopes → policy — **defer v0.2** (ADR-005).
11. Emergency/temporary access patterns — docs only.
12. `AccessPolicy` is readable/writable at `/fhir/R4/AccessPolicy` in Medplum JSON shape, partitioned per project (storage per ADR-001; same pattern reused for the other admin types in BB-R-014.3).

Exit test: membership with `{resourceType: Observation, criteria: "subject=%patient", hiddenFields:[note]}` — the user reads only their own Observations, `note` is absent, PUT on another patient's Observation → 403, and an `AuditEvent` records the denial (AuditEvent detail fills in v0.2, denial log in v0.1).

## BB-R-007 Subscriptions
Medplum ref: `subscriptions/` (index, publish-and-subscribe, subscription-extensions, server-scoped-subscriptions); `api/fhir/operations/resend`
Fill: **wire HAPI Subscriptions** (+ glue for Medplum extensions where cheap) · Version: v0.1 · Status: blessed

1. R4 `Subscription` with `criteria` (search string) and `channel.type = rest-hook`; fires on create/update/delete matching the criteria, scoped to the project.
2. Interaction filter extension (`create`-only / `update`-only / `delete`) — HAPI supports; map the Medplum extension URL.
3. Retry with backoff; configurable max attempts; delivery status visible as one `AuditEvent` per delivery attempt, searchable per Subscription (ADR-004; BALP/redaction stays BB-R-018 v0.2).
4. Signature header (`X-Signature`, HMAC of body with a per-subscription secret) — **glue** if HAPI lacks it; required for n8n/bot trust.
5. `$resend` operation to re-fire a resource's subscriptions — **glue** (thin).
6. Expression-based criteria (FHIRPath `%previous` vs `%current`) — HAPI `in-memory` matcher gap; **defer v0.2**.
7. AuditEvent as destination / log-only — **defer v0.2** with BB-R-012 audit.
8. WebSocket subscriptions — **defer v0.2**.
9. Server-scoped (cross-project) subscriptions for super-admin — **defer v0.2**.

Exit test: Subscription `criteria: Patient?`, rest-hook to a local echo server; creating a Patient delivers a signed POST within 5s; an update-only subscription does not fire on create.

## BB-R-008 Low-code automations (n8n)
Medplum ref: `bots/` (as the "automate on an event" capability, low-code path)
Fill: **wire n8n** via BB-R-007 rest-hooks · Version: v0.1 (recipe + example; n8n container only in `full`) · Status: blessed

1. Documented recipe: Subscription → n8n Webhook node → n8n HTTP node writing back through `/fhir/R4` with a ClientApplication token.
2. One shipped example workflow (JSON) in `examples/n8n/`: "on Patient create, send welcome email".
3. Signature verification snippet for the webhook node.

Exit test: import the example into n8n, create a Patient, workflow run shows the payload.

## BB-R-009 Binary storage
Medplum ref: `fhir-datastore/binary-data`, `fhir-datastore/external-documents`, `self-hosting/presigned-urls`
Fill: **wire HAPI binary storage** (`lite`: database/local; `full`: MinIO) · Version: v0.1 · Status: blessed

1. `POST /fhir/R4/Binary` with raw body + `Content-Type` stores the blob; `GET` returns it with the original content type; range requests supported.
2. `Attachment.url` references to `Binary/id` resolve for authenticated callers; `DocumentReference` pattern documented.
3. Presigned/tokenised download URLs for `<img>`/`<video>` without a bearer header — **defer v0.2**.
4. Binary security context — **defer v0.2**.

Exit test: upload a 5 MB PDF, reference it from a DocumentReference, download it back byte-identical in both profiles.

## BB-R-010 Notifications
Medplum ref: `user-management/custom-emails`, `user-management/project-smtp`, `self-hosting/sendgrid`, `self-hosting/mailtrapio`
Fill: **wire Keycloak email + Spring Mail** · Version: v0.1 · Status: blessed

1. Invite, password reset, email verification, MFA enrolment emails via server-wide SMTP config.
2. Per-project SMTP and branded templates — **defer v0.2**.
3. `lite` ships with a mail catcher optional container documented (not required for install).

Exit test: invite from BB-R-005 lands in the mail catcher with a working set-password link.

## BB-R-011 Packaging & install
Medplum ref: `self-hosting/` (index, running-full-medplum-stack-in-docker, install-from-scratch, install-on-kubernetes, server-config, setting-configuration, super-admin-guide, super-admin-cli, upgrading-server, disaster-recovery, monitoring, opentelemetry)
Fill: **glue** (Helm chart, compose, profiles, docs) · Version: v0.1 · Status: blessed

1. `docker compose up` with the `lite` file starts Postgres, HAPI, Keycloak, Big Book; ≤10 min on a laptop with a cold image cache; two commands max (mirrors Medplum's `curl … && docker compose up -d`). Admin UI reachable with the `admin` overlay (ADR-004; documented +3 min, outside the `lite` timer).
2. `helm install bigbook` with `profile: lite` produces the same stack on any Kubernetes; `profile: full` adds OpenSearch, MinIO, n8n, Traefik, Grafana stack, Vault, Snowstorm (per version).
3. Configuration by env/values only; every key documented in one table (`docs/guides/config.md`), with defaults; secrets never in values files.
4. First-boot bootstrap: realm, super-admin project, super-admin user, default policies; idempotent on restart.
5. Health endpoints for all components; `docker compose ps` shows healthy within the 10 minutes.
6. Pinned upstream versions in one place; upgrade guide per release.
7. Backup/DR, load testing, cloud-specific guides — **defer v0.3** (docs).

Exit test: fresh Ubuntu VM, `curl` + `docker compose up -d`, timer < 10 min, Baymax smoke script passes.

## BB-R-012 Java client SDK
Medplum ref: `sdk/core` (MedplumClient), `api/`; `fhir-datastore/working-with-fhir`
Fill: **glue** (thin layer over HAPI generic client + Keycloak) · Version: v0.1 · Status: blessed

1. `BigBookClient` with auth: client credentials, password (dev only), authorization code + PKCE (desktop/mobile), token refresh, `getProfile()` = `/auth/me`.
2. Typed CRUD on HAPI R4 structures: `create/read/vread/update/patch/delete/upsert(search)`.
3. `search(Class, params)` returning a page with `next()`; `searchOne`; `searchResources` (unwrapped); `_include` helpers.
4. `executeBatch/Transaction`; `graphql(query)`; `validate`.
5. Binary: `createBinary(InputStream, contentType)`, `download(url)`.
6. Admin helpers: `inviteUser`, `createClient`, `createProject` (super-admin).
7. Spring Boot starter: `bigbook.url/client-id/secret` → autoconfigured bean.
8. Autobatching — **non-goal** (server-side batches are enough).

Exit test: Baymax's patient read/write path runs on the SDK alone — no raw HTTP.

## BB-R-013 Admin UI (low-code)
Medplum ref: `app/` (index, app-introduction, sign-in-page, admin-page, apps-tab, invite)
Fill: **wire Appsmith CE** on `/fhir/R4` + `/admin`, `admin` compose overlay, app JSON in `app/lowcode/` (ADR-004, decided 2026-09-12) · Version: v0.1 (low-code) → v0.3 (Vaadin Flow in the Big Book server, only if the `@medplum/app` redirect-sign-in check is negative) · Status: blessed

1. v0.1: sign-in with Appsmith accounts; Big Book calls via a super-admin `ClientApplication`; project switcher = `GET /admin/projects` + `X-Project` header. Per-user Keycloak OIDC and human attribution (`X-Medplum-On-Behalf-Of`) — **defer v0.2** (BB-R-024). Screen inventory per ADR-004 is the build list.
2. Resource browser: list any type with search bar, detail view, JSON editor (create/update), history tab, delete.
3. Admin page: project details/settings, Users (list, invite, edit membership/policy/admin), Patients, Clients (create → shows secret once), Secrets, Bots (**v0.2**), Sites (**non-goal**).
4. AccessPolicy editor (JSON with schema validation) and assignment.
5. Subscription list with last-delivery status.
6. Timeline/patient chart views, Apps tab, questionnaire builder — **non-goal v0.x**; that's Medplum React territory (ADR-004 / v1).

Exit test: without touching the API, an admin creates a project, invites a user with a policy, creates a client, and edits a Patient's JSON.

## BB-R-014 Wire-compatibility surface
Medplum ref: `api/` (fhir/overview, oauth/*, auth/*, project-admin/*); Medplum custom resource types (`api/fhir/medplum/*`: Project, ProjectMembership, AccessPolicy, ClientApplication, User, Login, Bot, UserConfiguration, …)
Fill: **glue**, scope fixed by **ADR-003** (decided 2026-09-12: option B v0.1, SDK-grade C v0.2, app-grade C v1.0) · Version: v0.1 / v0.2 / v1.0 per sub-item · Status: blessed

1. FHIR base `/fhir/R4` with Medplum-identical paths, headers (`Content-Type: application/fhir+json`), status codes (404/410/412/422 semantics).
2. OAuth paths `/oauth2/authorize|token|userinfo|logout` and `/auth/me` as in BB-R-004.
3. Medplum admin resource types readable/writable in Medplum JSON shape: `Project`, `ProjectMembership`, `AccessPolicy`, `ClientApplication`, `User` (read-only projection of Keycloak), `UserConfiguration` — **v0.2**. `Bot` v0.2, `Login` non-goal.
4. Admin routes `/admin/projects`, `/admin/projects/:id`, `/admin/projects/:id/invite|client|members` payload-compatible — **v0.2**.
5. Target per version: v0.2 = `@medplum/core` runs unmodified (SDK-grade); v1.0 = `@medplum/app` runs unmodified (app-grade), gated on the `Login` sign-in flow. Resolved by ADR-003.
6. Status/header alignment with Medplum: v0.1 by HAPI config (UUID ids, no update-as-create, `_count` cap 1000, `application/fhir+json`, `ETag`); v0.2 glue: OperationOutcome `id`/`issue.code` mapping, 409→412 on `If-Match`, 422→400 on validation, `X-Trace-Id`.

Exit test (v0.1): Medplum's own `curl` examples from `fhir-datastore/creating-data` and `api/oauth/token` work against Big Book unchanged; `GET /.well-known/openid-configuration` advertises Big Book URLs and a token from `/oauth2/token` validates against `/.well-known/jwks.json`.
Exit test (v0.2): `@medplum/core` `MedplumClient` with `baseUrl` pointed at Big Book completes client-credentials login, `/auth/me`, and a Patient create/read/search without patching.

## BB-R-015 Observability (minimum)
Medplum ref: `self-hosting/monitoring`, `self-hosting/opentelemetry`
Fill: **wire OpenTelemetry** (exporters on in `full`; `lite` logs to stdout) · Version: v0.1 minimum, v0.2 full stack · Status: blessed

1. Structured JSON logs with request id, project id, user id on every request.
2. `/actuator/health`, `/actuator/metrics`; OTel traces off by default in `lite`.

Exit test: one `curl` to `/fhir/R4/Patient` produces one log line carrying project + user.

---

# v0.2 — placeholders (specified after v0.1 is blessed)

| ID | Brick | Fill | Medplum ref |
|---|---|---|---|
| BB-R-016 | Bot SDK (Camel, `@Bot` on a route; triggers: subscription, cron, `$execute`; deploy via CLI) | glue | `bots/` |
| BB-R-017 | CLI (login, CRUD, bulk import, bot deploy) | glue picocli | `cli/` |
| BB-R-018 | Audit events (BALP on every access; `redactAuditEvents`) | wire HAPI | `compliance/` |
| BB-R-019 | Consent enforcement | wire HAPI ConsentInterceptor | `consent/` |
| BB-R-020 | Bulk export `$export` | wire HAPI | `api/fhir/operations` |
| BB-R-021 | Full-text search (`full`) | wire OpenSearch | `search/` |
| BB-R-022 | SMART App Launch scopes + launch context | wire Keycloak ext (ADR-005) | `integration/smart-app-launch`, `access/smart-scopes` |
| BB-R-023 | Rate limits per project/user | wire Traefik | `rate-limits` |
| BB-R-024 | On-Behalf-Of (incl. `X-Medplum-On-Behalf-Of` for admin clients), direct external auth, open patient registration, project linking | wire Keycloak / HAPI | see v0.1 deferrals |
| BB-R-025 | Subscription extras (WebSocket, expression criteria, AuditEvent destination, server-scoped) | wire/glue | `subscriptions/` |
| BB-R-026 | Per-project SMTP + branded emails | wire Spring Mail | `user-management/custom-emails` |
| BB-R-027 | Presigned URLs + binary security context (MinIO) | wire MinIO | `self-hosting/presigned-urls` |
| BB-R-014.3/4/6 | SDK-grade Medplum compat: custom resource types, `/admin/projects/*` payloads, status/header glue | glue | `api/`, ADR-003 |

# v0.3 — placeholders

HL7v2 agent (`agent/`, `integration/hl7-interfacing`), terminology with Snowstorm (`terminology/`), BD IG v1, proper admin UI (ADR-004), migration guide + CLI import (`migration/`), DR/backup/cloud guides.

# v1.0

App-grade Medplum compat (`@medplum/app` unmodified) — blocked on emulating the `Login` sign-in flow. Confirmed 2026-09-12 (issue #1): `@medplum/app` has no config path to `signInWithRedirect` or a direct OIDC redirect; even the `DomainConfiguration` external-IdP route runs through the `Login` protocol. Stays v1.0; see `docs/guides/medplum-parity.md`.

# Non-goals (v0.x)

Medplum Provider app; clinical workflow features (intake, charting, scheduling, orders, meds, care plans, comms, billing, questionnaire builder, protocols, provider directory); US integrations (DoseSpot, ScriptSure, Health Gorilla, Stedi, Candid, eFax, Twilio); DICOM, FHIRcast, CDS Hooks, C-CDA, ePA, Analytics/Athena, AI features; React component library (revisit at v1 per ADR-003/004); SCIM; mTLS; patient dedup pipeline; SDK autobatching.
