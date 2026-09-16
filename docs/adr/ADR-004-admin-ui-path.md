# ADR-004 Admin UI path

Status: decided (2026-09-12), amended (2026-09-17, inventory reconcile — D1, D2, D5, D43)
Decision: **Appsmith CE as a compose overlay in v0.1 (service-account UI); Vaadin Flow in v0.3; Medplum React app not before v1.0.** (Issue #1 resolved 2026-09-12: no config path in `@medplum/app`, so the Vaadin line stands.)
Context: see `docs/BIGBOOK.md` → Open decisions; requirements unblocked by this: BB-R-013; consequences land in BB-R-005, BB-R-007, BB-R-011

## Options

- **A** — Appsmith CE (or ToolJet) on the REST API. Low-code, JSON-exportable app definition, no Big Book UI code. Not in `lite`; ships as an overlay.
- **B** — Medplum React app (`@medplum/app`) unmodified. Requires app-grade C per ADR-003, currently v1.0; moves to v0.2 only if `@medplum/app` can be pointed at an external OIDC login by configuration (issue #1, task 1).
- **C** — Vaadin Flow (all-Java, in the Big Book server). Real code to own; only worth it if B never becomes available.

## Decision

| Version | Admin UI | Sign-in model |
|---|---|---|
| v0.1 | **Appsmith CE**, `deploy/compose/admin.yml` overlay, not part of the 10-minute `lite` install (+≤3 min documented separately) | **Service account**: one super-admin `ClientApplication`; the app selects the target project with the optional `X-Project` header (BB-R-005.11). No per-user identity in the UI. If Appsmith calls Big Book from the browser, CORS echoes the origin with credentials allowed (D43). |
| v0.2 | Same overlay | Per-user OIDC sign-in and On-Behalf-Of (BB-R-004.6) so actions are attributed to the signed-in user |
| v0.3 | **Vaadin Flow** proper admin UI — condition met: issue #1 found `@medplum/app` cannot be configured for external OIDC without patching (ADR-003 → Open) | Per-user OIDC |
| v1.0 | `@medplum/app` per ADR-003 app-grade C | Medplum `Login` flow emulation |

Build list for v0.1 = `app/lowcode/SCREENS.md` (11 screens, PO-owned). Every page calls only the endpoints listed for it there. Nothing more gets designed before code. Visual reference: the Medplum app screenshot pass in `docs/reference/medplum-app/` (issue #20).

## Screen inventory (v0.1)

See `app/lowcode/SCREENS.md`. Gap the screen inventory exposed: S5/S6/S8/S9 call admin routes that issue #6 did not define — `GET /admin/projects`, `GET/PUT /admin/projects/:id`, `GET /admin/projects/:id/members[?profileType=]`, `PUT /admin/projects/:id/members/:mid`. Added to #6. **Amended 2026-09-17 (D2):** none of the four exists in Medplum (it lists projects via `/auth/me` + FHIR `Project` search, edits a project via `POST …/settings|secrets|sites`, lists members via FHIR `ProjectMembership?profile-type=`, and updates a member via `POST …/members/:mid`). They are **BB-only** routes (BB-R-005.12), kept for Appsmith, never documented as Medplum-compatible; BB-R-014.4 now lists Medplum's real spellings as the v0.2 compat surface.

## Consequences (folded into issues)

- **#2** — `lite` stays three containers (ADR-006); "admin UI reachable" leaves the install AC. `deploy/compose/admin.yml` overlay documented with its own +3 min note.
- **#4** — `X-Project` header selects the target partition for super-admin tokens only; a non-super-admin sending it gets 403. **Amended 2026-09-17 (D1):** the header is a Big Book extension and is **optional, never required** — Medplum has no such header and `@medplum/core` never sends it; where a route has a `:projectId` path segment the path wins; a super-admin call without either acts on the super-admin's own project (Medplum behaviour). The earlier "a super-admin write without it gets 400" is withdrawn.
- **#6 / #16** — `ClientApplication.secret` is **retrievable** by project admins on every read of the resource, as in Medplum and Keycloak (D5); the Clients screen shows it on the detail page. BB-R-013.3's "shows secret once" is withdrawn.
- **#12** — one `AuditEvent` per delivery attempt (outcome, attempt number, HTTP status), searchable by Subscription reference, minimal fields, no BALP. The Subscriptions screen reads it.
- **#16** — rewritten: Appsmith CE overlay, service-account model, verify-first items below, ≤3 min overlay budget, no Business/Enterprise features.
- **#1** — unchanged; its task 1 decides whether the v0.3 Vaadin line exists.

## Rationale

- Appsmith CE gets BB-R-013 to its exit test with zero Big Book UI code and a JSON artefact we can commit. ToolJet was the alternative; Appsmith's JSON-editor widget and REST datasource OAuth are the deciding features.
- Keeping it out of `lite` protects the 10-minute install (BB-R-011.1) and keeps the Baymax dogfood stack minimal.
- Service-account sign-in in v0.1 avoids building per-user OIDC into a low-code tool before On-Behalf-Of exists (BB-R-004.6, v0.2). Cost: v0.1 admin actions are attributed to the service account, not a person. Acceptable for a dogfood release.
- Vaadin is real code and only justified if the Medplum app path is closed. Issue #1 answers that before any v0.3 planning.

## Open

Verify on the pinned Appsmith CE version before building #16; record each as resolved here.

1. REST datasource OAuth (client credentials) is datasource-level in CE — not a Business feature.
2. Git-sync limits in CE; if unusable, JSON export/import is the commit path for `app/lowcode/bigbook-admin.json`.
3. App auto-import at first boot is scriptable via the import API; else the manual import is documented in `docs/guides/admin-ui.md`.
