# ADR-007 Cross-store provisioning

Status: decided (2026-09-18)
Decision: **Reconcile, never compensate.** Every operation that writes to more than one store (Big Book Postgres, Keycloak, HAPI partition/resource) anchors on a Big Book row written first with `status = provisioning`; every following step is idempotent by a stable key; on any failure the request returns an `OperationOutcome` and the row stays `provisioning`; retrying the same request resumes from the failed step; a startup reconciler finishes or reports stragglers. No compensating deletes.
Context: surfaced by the Architect while drawing `docs/ARCHITECTURE.md` §2(e) create-project. BB-R-011.4 says "idempotent on restart" for bootstrap only; BB-R-005 was silent on runtime creates. Applies to: create project (BB-R-005.1), invite (BB-R-005.4), client (BB-R-005.5), bootstrap (BB-R-011.4).

## Stable keys
- Keycloak organisation: `alias = project id`.
- HAPI partition: `name = project id`.
- Keycloak user: email, within the organisation (scope `project`) or realm (scope `server`).
- Keycloak confidential client: `clientId = ClientApplication id`.
- Profile resource: conditional create on `identifier = email` (or client id) in the project partition.

## Rationale
- Three stores, no shared transaction. Keycloak is another process; the `hapi` and `bigbook` schemas may be separate databases — the pattern does not depend on that.
- Compensation can itself fail and leave an organisation with no owner or a partition with no project. An idempotent forward path converges on retry instead.
- Medplum has the same shape (Project + membership + profile in one call) and the same silence on partial failure; this is a deliberate improvement, not a divergence (`docs/guides/medplum-parity.md`).

## Consequences
- `bigbook.project.status` and `bigbook.project_membership.status` ∈ {`provisioning`, `active`}; one status column each.
- Startup reconciler: sweeps rows in `provisioning` older than N minutes, re-runs the idempotent steps, marks `active` or logs a structured straggler report. ≈60 glue lines, charged to BB-R-005. v0.1 glue tally → ≈5.2k.
- **Visibility (BB-R-005.13):** a Project or ProjectMembership in `provisioning` returns 404 to every non-super-admin call, issues no tokens, and is listed with its status in `GET /admin/projects` for the super-admin only.
- Email after the anchor row is `active` (invite) is best-effort: failure → 200 with a warning `OperationOutcome` (Medplum behaviour, inventory T5).
- Exit test, on issues #4 and #6: stop Keycloak between two steps of a create-project / invite; the request returns an `OperationOutcome`; the row is `provisioning`; restart Keycloak; retry the identical request; assert the row is `active`, exactly one organisation / partition / user / profile exists, and nothing was deleted at any point.
- `docs/ARCHITECTURE.md` §2(e)/(f) carry the failure branch; §2(g) is unaffected.

## Reversal cost
Low. The step order is the same under compensation or reconciliation; reversing means adding delete calls per step and removing the reconciler. Trigger: none foreseen — reconciliation is the safer default at any scale.

## Open — Claude Code tasks, tracked on issue #4
- Confirm Keycloak Admin REST returns 409 (not 400/500) on duplicate organisation alias and duplicate user email on the pinned version, so "already exists → continue" is a status check, not a search.
- Confirm HAPI partition creation is idempotent by name or needs a lookup-then-create.
