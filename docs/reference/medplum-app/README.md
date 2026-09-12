# Medplum App reference screenshots (ADR-004 §5)

One-time capture from `app.medplum.com`. Never refreshed. Reference only for ADR-004 / issue #16; not a spec.

Capture rules: 1440 px wide, light theme, one PNG per line below, filename = slug of the line.

1. Sign-in page
2. Project chooser (multi-project account)
3. Resource list (Patient) with search bar and column picker
4. Resource list (Observation) with a chained / `_include` search applied
5. Resource detail — Details tab
6. Resource detail — Edit tab (form)
7. Resource detail — JSON tab
8. Resource detail — History tab
9. Resource detail — Delete confirmation
10. Admin → Project (details, settings, secrets)
11. Admin → Users list
12. Admin → Invite form (all fields, incl. access policy + MFA required)
13. Admin → Patients
14. Admin → Clients list + Create client + secret dialog
15. Admin → Bots (v0.2 reference only)
16. AccessPolicy list and one AccessPolicy JSON tab
17. Subscription list and one Subscription detail
18. Super Admin page (BB-R-005.7 reference)
19. Security page (MFA / sessions — BB-R-004 reference)

## Capture record (2026-09-12, issue #20)

Captured from a local `docker-compose.full-stack.yml` stack, not `app.medplum.com` (no hosted credentials in the session): server `5.1.37-a9b62fb`, app `5.1.37-2436608`. Seeded super-admin account; data seeded so lists are non-empty. Files are `NN-<slug>.png`, 1440 px wide, light theme; three are two states stitched vertically because the line asks for both. The stack was torn down afterwards; every id and secret visible in the shots is ephemeral.

Where the app differed from the line above:

- 04 — `subject.name=` chaining errors in this version; captured `patient.name=Simpson&_include=Observation:subject`. The list shows no search-string bar; the `_include`d Patient appears as a row.
- 09 — no Delete tab; deletion is Edit → More actions → Delete, which opens `/Patient/:id/delete` with an inline confirmation.
- 13 — `/admin/patients` redirects to `/admin/users`; captured with the Patient profile-type filter.
- 14 — no secret dialog; creation shows "Client created", the secret is revealed on the ClientApplication Details tab via "Show secret". Both states stitched.
- 15 — one Bot created via the admin API so the list is non-empty.
