# ADR-003 Medplum wire-compatibility scope

Status: decided (2026-09-12), amended (2026-09-17, inventory reconcile — D3/D51, D24, D49, D50/T49, T50)
Decision: **B in v0.1, SDK-grade C in v0.2, app-grade C in v1.0.** SDK-grade means the supported method subset in inventory area 5; app-grade means the project-user surface of `@medplum/app`.
Context: see `docs/BIGBOOK.md` → Open decisions; requirements unblocked by this: BB-R-004, BB-R-005, BB-R-013, BB-R-014

## Options

- **A** — FHIR + OAuth endpoints only. `curl`-level compat; no Medplum SDK support.
- **B** — A + `/auth/me` glue. Medplum-shaped OAuth paths proxied/re-shaped from Keycloak; `/auth/me` returns profile, project, membership, access policy, user config. Enough for the SDK to log in and talk FHIR.
- **C** — B + Medplum custom resource types (`Project`, `ProjectMembership`, `AccessPolicy`, `ClientApplication`, `User`, `UserConfiguration`, `Bot`, …) + `/admin/projects/*` routes + status/header alignment. Split into two grades:
  - **SDK-grade C** — `@medplum/core` `MedplumClient` runs unmodified for the **supported method subset** (D50: inventory area 5 Table A, "Java SDK disposition" column). Out of scope, by family: the `Login`-protocol auth methods (`startLogin`, `startNewUser`, `startNewProject`, `startNewPatient`, `startGoogleLogin`, `signInWithExternalAuth`, MFA helpers), FHIRcast, CDS Hooks, `keyvalue/*`, schema introspection (`requestSchema`, `requestProfileSchema`, `$expand-profile`), `pushToAgent`, `Bot/$execute` (until BB-R-016), PDF helpers. A program that does not call them does not need them. The 31-item server contract is area 5 note (a).
  - **App-grade C** — `@medplum/app` runs unmodified on the **project-user surface** (D24): sign-in, resource browse/edit, project admin pages. The super-admin database and migration console is out of scope by design — twelve of its operations (`$db-stats`, `$db-indexes`, `$db-invalid-indexes`, `$db-schema-diff`, `$db-column-statistics`, `$db-configure-*`, `$db-index-bloat`, `$explain`, migrations) are Postgres catalog introspection and `ALTER` statements over HTTP against Medplum's own schema, which Big Book on HAPI can never answer. Additionally needs the `Login` resource and `/auth/login|newuser|…` sign-in flow, `User` writes, and the GraphQL schema-introspection connections (T53).

## Decision

| Version | Scope | Proof |
|---|---|---|
| v0.1 | **B** | Medplum's own `curl` examples for FHIR create/read and `api/oauth/token` work unchanged; `/auth/me` returns correct project + profile (BB-R-004 §8, BB-R-014 exit test v0.1) |
| v0.2 | **SDK-grade C** | (D49 — the previous smoke test passed on a server failing six contract items.) An unmodified `@medplum/core` script must, in one run: (1) complete `startClientLogin`; (2) have `getProfile()`, `getProject()`, `getProjectMembership()`, `getAccessPolicy()` all non-undefined; (3) `createResource` a Patient and get the body back with a server id; (4) `createResourceIfNoneExist` it and get the **existing** one; (5) `upsertResource` by identifier; (6) read, delete, read again and assert `isGone`; (7) read a random UUID and assert `isNotFound`; (8) create 1001 Patients and assert `searchResourcePages` yields two pages; (9) `readHistory({offset:1})` returns a different page; (10) the whole script runs from a browser on a different origin. Items 6, 7 and 10 are what make it a compat test (BB-R-014 exit test v0.2). |
| v1.0 | **App-grade C** | `@medplum/app` runs unmodified against Big Book on the project-user surface; gated on implementing the `Login` sign-in flow (BB-R-014 §5) |

## Rationale

- v0.1's exit criterion is Baymax reading/writing patient records, not Medplum tooling. B is the smallest surface that keeps the door open for the SDK without building admin-route glue before the admin model (BB-R-005) settles.
- `/auth/me` lands in v0.1 rather than v0.2 because it is cheap (one projection over Keycloak claims) and it is the single call that decides whether `MedplumClient` can start at all. Deferring it would force a breaking OAuth re-shape later.
- SDK-grade C is bounded and testable: one client library, one exit test. It is the level external pilot users (v0.3) actually need.
- App-grade C drags in the `Login` resource and Medplum's password/registration flows, which Keycloak replaces (BB-R-004 §8a). That is a v1.0 concern and depends on ADR-004 choosing the Medplum React app as the admin UI; if ADR-004 goes elsewhere, app-grade C can be dropped without touching v0.x.

## Consequences

- Token issuer is the Big Book public URL; `/.well-known/openid-configuration` and `/oauth2/logout` are re-shaped by Big Book; `/oauth2/authorize`, `/oauth2/userinfo` and `/.well-known/jwks.json` proxied to Keycloak unchanged (BB-R-004 §8). **`/oauth2/token` is proxied unchanged in v0.1 and re-shaped from v0.2** (D3/D51): the SDK reads `project` and `profile` from the token response body and requires `exp`, `client_id` (or `cid`) and `login_id` claims — without `login_id` it never calls `/auth/me` and behaves as signed-out while holding a valid token. Keycloak mappers supply the claims; a response wrapper supplies the two body fields.
- Access-token lifespan ≥ 15 min in the shipped realm (D42) and CORS with credentials (D43) are v0.1 configuration that SDK-grade depends on; both are zero-cost and land early.
- Server-assigned UUID ids only; update-as-create rejected as in Medplum (BB-R-001 §9) — HAPI config in v0.1, no glue.
- v0.2 adds glue for OperationOutcome mapping, 409→412 on `If-Match`, 422→400 on validation, `X-Trace-Id` (BB-R-014 §6).
- `Login`, `/auth/login|newuser|newproject|newpatient|profile|scope|mfa/*|changepassword|resetpassword|setpassword|verifyemail` stay non-goal through v0.x.
- ADR-004 (admin UI path) may now assume wire-compat exists from v0.2 for the SDK and from v1.0 for the app.

## Open

Resolved by issue #1 on 2026-09-12 (research against `medplum-server` 5.1.37, source at `main` `fbc8e7b4`).

1. **Can `@medplum/app` reach `signInWithRedirect` / external OIDC by configuration alone?** — **No.** The app's config surface is six env keys (`packages/app/src/config.ts`); none wires `authorizeUrl`/`tokenUrl`, and `packages/app` never calls `signInWithRedirect` or `signInWithExternalAuth`. The only external-IdP route is a server-side `DomainConfiguration`, and it still runs through `POST /auth/method`, `GET /auth/external`, the `Login` resource, `GET /auth/login/:id`, `POST /oauth2/token` and `GET /auth/me`. App-grade C therefore requires emulating the `Login` protocol and **stays v1.0**. Consequence for ADR-004: the v0.3 Vaadin Flow line stands.
2. **Medplum edge-case status codes** — recorded in `docs/guides/medplum-parity.md`. Headline divergences from HAPI defaults: stale `If-Match` is 412 in Medplum vs 409 in HAPI; update-as-create is 200 for super-admin and 404 for everyone else in Medplum vs 201 in HAPI; Medplum sets `OperationOutcome.id` to a stable slug that `@medplum/core` helpers key on, HAPI sets none. All are v0.2 glue under BB-R-014.6; v0.1 needs only the HAPI config in #8. Added 2026-09-17 (T50): two further slugs the SDK keys on, `multiple-matches` (412, ambiguous conditional operation) and `business-rule` (the only path to 422); and `getStatus()` defaults an unrecognised `id` to 400 (500 when `issue[0].code === 'exception'`), which is why a HAPI error with no `id` reads as 400 to every SDK helper. The full v0.2 glue list is BB-R-014.6 (eight items added by T29).
