# ADR-005 SMART-on-FHIR Keycloak extension

Status: open — candidates evaluated (Architect, 2026-09-18); decision pending the four verify items below
Decision: —
Context: see `docs/BIGBOOK.md` → Open decisions; requirements blocked on this: BB-R-022 (v0.2). Scope → AccessPolicy compilation, `.well-known/smart-configuration` and the `SmartAppLaunch` resource are Big Book's under every candidate (ADR-001 translator input; ADR-003 v0.2 token wrap).

## Options

- **A** — Alvearie `keycloak-extensions-for-fhir` (IBM, Apache 2.0).
- **B** — zedwerks `keycloak-smart-fhir` (Apache 2.0, single maintainer).
- **C** — None: pinned Keycloak native features + the Big Book `/oauth2/token` wrap that ADR-003 already schedules for v0.2.

## Candidates (2026-09-18)

Evidence gathered by the Architect; no decision. Requirement: BB-R-022 (v0.2). What SMART needs from the IdP: accept SMART scope strings; validate the `aud` request parameter against the FHIR base; resolve `launch` / `launch/patient` context; return `patient` (and `encounter`, `fhirContext`) in the **token response body**; `fhirUser` claim. Scope → AccessPolicy compilation, `.well-known/smart-configuration` and the `SmartAppLaunch` resource are Big Book's regardless of candidate (ADR-001 translator input; ADR-003 v0.2 token wrap already exists).

| | Alvearie `keycloak-extensions-for-fhir` | zedwerks `keycloak-smart-fhir` | None — Keycloak native + Big Book token wrap |
|---|---|---|---|
| Licence | Apache 2.0 (IBM) | Apache 2.0 (single maintainer) | — |
| Keycloak target | WildFly era: `jboss-fhir-provider` modules, `/auth` URL prefix, `KEYCLOAK_USER` env, OpenJDK 11 image; Docker image last pushed 4+ years ago; 4 tags, no releases, 13 open issues, 6 open PRs | README states Keycloak **26.3.5**; notes 26.4.x breaks its Terraform config; 571 commits, CodeQL enabled | pinned Keycloak, no extension jar |
| `aud` validation | Authenticator, allow-list | Authenticator, allow-list; accepts `aud` / `audience` / `resource` aliases | **not native** — Keycloak issues `aud` via mapper but does not validate the request parameter |
| `launch/patient` (standalone) | Patient-picker authenticator: reads `resourceId` user attribute, batch-reads Patient from the FHIR server, shows a selection form | not the focus (EHR-launch) | not native; would be a Big Book page or a Keycloak required action |
| `launch` (EHR launch) | — | Authenticator resolves `launch` token via an external **Context API** server the deployer must run | Big Book resolves `launch` in the token wrap from its `SmartAppLaunch` table (Medplum's own design) |
| `patient` in token response | User Session Note mapper (Keycloak built-in ≥12) + forked attribute mapper | custom mapper, body + bearer | User Session Note mapper is built-in; Big Book wrap can also inject |
| `fhirUser` | prefix mapper | custom mapper | built-in user-attribute mapper with a hardcoded prefix, or wrap |
| Scope strings | configurator creates SMART v1 client scopes | Terraform creates them | v1 fixed scopes as client scopes in realm import; v2 granular (`patient/Observation.rs?…`) needs Keycloak **dynamic scopes** (preview) or Big Book accepting them at the wrap |
| Realm setup | Java configurator, JSON-driven | Terraform modules, mandatory flow structure | realm import JSON (already BB-R-011.4) |
| Maintenance risk | effectively unmaintained; would need a port to Quarkus Keycloak = fork | one person; pinned to a Keycloak minor; Terraform dependency Big Book does not otherwise use | Keycloak SPI churn avoided entirely; Big Book carries the SMART logic in the same JVM that already wraps `/oauth2/token` |

Observations, not decisions:
- Alvearie is the right design but the wrong era; adopting it means owning a port.
- zedwerks is current but EHR-launch-centric and drags in a Context API service and Terraform — two things the `lite` and `full` profiles don't have.
- The "none" column covers everything except `aud` request-parameter validation and a standalone patient picker; the first is ~30 lines as a Keycloak authenticator **or** a check in Big Book's `/oauth2/authorize` redirect (Big Book already sits in front of it, ADR-003), the second is a v0.3-shaped UI question.

Verify before deciding (Claude Code, on the pinned Keycloak):
1. zedwerks and Alvearie last-commit dates (not captured in this pass).
2. Dynamic-scopes feature status and whether it accepts SMART v2 strings with `?` parameters.
3. `organization:<alias>` scope binds a token to one organisation; bare `organization` triggers the built-in selector (also needed for ARCHITECTURE §2(g)).
4. Whether the built-in User Session Note mapper can populate the token **response body** (not just claims) on the pinned version.
