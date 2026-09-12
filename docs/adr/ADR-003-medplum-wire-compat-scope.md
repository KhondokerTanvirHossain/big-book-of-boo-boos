# ADR-003 Medplum wire-compatibility scope

Status: decided (2026-09-12)
Decision: **B in v0.1, SDK-grade C in v0.2, app-grade C in v1.0.**
Context: see `docs/BIGBOOK.md` → Open decisions; requirements unblocked by this: BB-R-004, BB-R-005, BB-R-013, BB-R-014

## Options

- **A** — FHIR + OAuth endpoints only. `curl`-level compat; no Medplum SDK support.
- **B** — A + `/auth/me` glue. Medplum-shaped OAuth paths proxied/re-shaped from Keycloak; `/auth/me` returns profile, project, membership, access policy, user config. Enough for the SDK to log in and talk FHIR.
- **C** — B + Medplum custom resource types (`Project`, `ProjectMembership`, `AccessPolicy`, `ClientApplication`, `User`, `UserConfiguration`, `Bot`, …) + `/admin/projects/*` routes + status/header alignment. Split into two grades:
  - **SDK-grade C** — `@medplum/core` `MedplumClient` runs unmodified.
  - **App-grade C** — `@medplum/app` runs unmodified; additionally needs the `Login` resource and `/auth/login|newuser|…` sign-in flow.

## Decision

| Version | Scope | Proof |
|---|---|---|
| v0.1 | **B** | Medplum's own `curl` examples for FHIR create/read and `api/oauth/token` work unchanged; `/auth/me` returns correct project + profile (BB-R-004 §8, BB-R-014 exit test v0.1) |
| v0.2 | **SDK-grade C** | `@medplum/core` `MedplumClient` with `baseUrl` pointed at Big Book completes client-credentials login, `/auth/me`, and Patient create/read/search without patching (BB-R-014 §3–6, exit test v0.2) |
| v1.0 | **App-grade C** | `@medplum/app` runs unmodified against Big Book; gated on implementing the `Login` sign-in flow (BB-R-014 §5) |

## Rationale

- v0.1's exit criterion is Baymax reading/writing patient records, not Medplum tooling. B is the smallest surface that keeps the door open for the SDK without building admin-route glue before the admin model (BB-R-005) settles.
- `/auth/me` lands in v0.1 rather than v0.2 because it is cheap (one projection over Keycloak claims) and it is the single call that decides whether `MedplumClient` can start at all. Deferring it would force a breaking OAuth re-shape later.
- SDK-grade C is bounded and testable: one client library, one exit test. It is the level external pilot users (v0.3) actually need.
- App-grade C drags in the `Login` resource and Medplum's password/registration flows, which Keycloak replaces (BB-R-004 §8a). That is a v1.0 concern and depends on ADR-004 choosing the Medplum React app as the admin UI; if ADR-004 goes elsewhere, app-grade C can be dropped without touching v0.x.

## Consequences

- Token issuer is the Big Book public URL; `/.well-known/openid-configuration` and `/oauth2/logout` are re-shaped by Big Book, everything else proxied to Keycloak unchanged (BB-R-004 §8).
- Server-assigned UUID ids only; update-as-create rejected as in Medplum (BB-R-001 §9) — HAPI config in v0.1, no glue.
- v0.2 adds glue for OperationOutcome mapping, 409→412 on `If-Match`, 422→400 on validation, `X-Trace-Id` (BB-R-014 §6).
- `Login`, `/auth/login|newuser|newproject|newpatient|profile|scope|mfa/*|changepassword|resetpassword|setpassword|verifyemail` stay non-goal through v0.x.
- ADR-004 (admin UI path) may now assume wire-compat exists from v0.2 for the SDK and from v1.0 for the app.

## Open

Resolved by issue #1 on 2026-09-12 (research against `medplum-server` 5.1.37, source at `main` `fbc8e7b4`).

1. **Can `@medplum/app` reach `signInWithRedirect` / external OIDC by configuration alone?** — **No.** The app's config surface is six env keys (`packages/app/src/config.ts`); none wires `authorizeUrl`/`tokenUrl`, and `packages/app` never calls `signInWithRedirect` or `signInWithExternalAuth`. The only external-IdP route is a server-side `DomainConfiguration`, and it still runs through `POST /auth/method`, `GET /auth/external`, the `Login` resource, `GET /auth/login/:id`, `POST /oauth2/token` and `GET /auth/me`. App-grade C therefore requires emulating the `Login` protocol and **stays v1.0**. Consequence for ADR-004: the v0.3 Vaadin Flow line stands.
2. **Medplum edge-case status codes** — recorded in `docs/guides/medplum-parity.md`. Headline divergences from HAPI defaults: stale `If-Match` is 412 in Medplum vs 409 in HAPI; update-as-create is 200 for super-admin and 404 for everyone else in Medplum vs 201 in HAPI; Medplum sets `OperationOutcome.id` to a stable slug that `@medplum/core` helpers key on, HAPI sets none. All are v0.2 glue under BB-R-014.6; v0.1 needs only the HAPI config in #8.
