# Medplum capability inventory

> What Medplum's server actually does, row by row, and what Big Book does about each row. Source-first: the rows come from `medplum/medplum` code, the docs supply the "why" link, the examples supply the "who needs it" column. Evidence behind `docs/REQUIREMENTS.md`; observed status codes live in `docs/guides/medplum-parity.md`.
> **Frozen** at snapshot `medplum/medplum` `main` @ `fbc8e7b4b` (2026-09-11, server 5.1.x). Do not edit the area files; a newer Medplum gets a new inventory pass with a new hash. Big Book decisions are recorded in REQUIREMENTS.md and the ADRs, not here.
> Blessed wholesale 2026-09-17 with four exceptions (D9, D23, D54, D56); reconciled into REQUIREMENTS.md, ADR-001/002/003/004, ARCHITECTURE.md and BIGBOOK.md the same day.

Docs scrape used for the "Doc ref" column: 2026-09-12, `docs/medplum-docs/` (git-ignored; regenerate with `tools/scrape_medplum_docs.py`).

## Files

| Area | File | Scope | Rows | Status |
|---|---|---|---|---|
| 1 | [area-1-unique-layer.md](area-1-unique-layer.md) | Admin routes, 24 custom resource types (+ element tables), auth/OAuth | 46 + 31 + ~190 + 74 | reconciled |
| 2 | [area-2-fhir-routes-repo-search.md](area-2-fhir-routes-repo-search.md) | FHIR routes and envelope, repository, search and GraphQL | 71 + 72 + 100 | reconciled |
| 3 | [area-3-operations.md](area-3-operations.md) | The 85 FHIR operations | 40 + 45 | reconciled |
| 4 | [area-4-configuration.md](area-4-configuration.md) | 158 server config keys, 41 project settings, 17 feature flags | 216 | reconciled |
| 5 | [area-5-medplum-client.md](area-5-medplum-client.md) | `MedplumClient` public surface, SDK-grade contract (31 items) | 101 + 16 | reconciled |
| 6 | [area-6-workers-subscriptions.md](area-6-workers-subscriptions.md) | Workers, subscription delivery, WebSocket | 54 | reconciled |

840 rows. 121 rows carry `GAP` (no brick owned them when drafted); **every one has a proposed fate** — none was left without a disposition.

## Conventions

- **Row schema**: `ID | Capability | Source (file:line) | Behaviour | Doc ref (path — menu breadcrumb) | Used by | Proposed fate | BB-R`.
- **Fate**: `wire HAPI` / `wire Keycloak` (upstream does it; expose + configure), `glue` (Big Book code, counts against the 5–10k budget), `defer v0.x` (named version), `non-goal` (with reason).
- **BB-R**: the brick in REQUIREMENTS.md that owns the row; `GAP` = no brick mentioned it at draft time.
- **Source paths** are relative to `packages/server/src` unless stated; `client.ts` = `packages/core/src/client.ts`.
- **Decision rows**: **D** = design call, **T** = text fix (the brick was wrong about Medplum), **V** = verify on the pinned HAPI container. Numbered globally across areas.

## Disposition of the decision list (2026-09-17)

**A** = applied to a v0.1 brick or an ADR. **A\*** = applied with the bless exception. **P** = postponed; recorded in the v0.2/v0.3 placeholder tables of REQUIREMENTS.md with its D number. **T1–T62**: all 62 applied to REQUIREMENTS.md (ADR-001 also carries T1/T2/T3/T31; ADR-003 carries T49/T50/T53).

| D | Disposition | Landed in |
|---|---|---|
| D1 | A | BB-R-005.11; ADR-004 #4 (400 rule withdrawn) |
| D2 | A | BB-R-005.12, BB-R-014.4; ADR-004 screen inventory |
| D3 | P | BB-R-004.8, BB-R-014.3; ADR-003 consequences (token re-shaped from v0.2) |
| D4 | P | BB-R-014.3 |
| D5 | A | BB-R-005.5, BB-R-013.3; ADR-004 |
| D6 | P | BB-R-004.1, BB-R-024 |
| D7 | A / P | email-OTP non-goal (BB-R-004.3); project-wide `mfaRequired` → BB-R-024 |
| D8 | A | BB-R-011.7; non-goals |
| D9 | **A\*** | BB-R-004.2, BB-R-024 — preview flag in `full`; ~150 glue lines recorded as the expected end state |
| D10 | P | BB-R-017 |
| D11 | A | BB-R-001.4; divergences |
| D12 | A / P | BB-R-001.1 text; 304 glue → BB-R-014.3 |
| D13 | A | BB-R-001.9 |
| D14 | A | BB-R-006.1/13; ADR-001 enforcement path + hooks |
| D15 | A | BB-R-006.1, BB-R-007.1; ADR-001 write-time validation |
| D16 | A | BB-R-001.6, BB-R-005.1 |
| D17 | A | BB-R-002.7 |
| D18 | A / P | server-wide toggle + enforcing limits (BB-R-003.4); per-project toggle → v0.2 |
| D19 | A | BB-R-002.1 |
| D20 | A / P | HAPI behaviour kept; poll-URL alias → BB-R-020 |
| D21 | A | BB-R-023; non-goals |
| D22 | P | BB-R-018 |
| D23 | **A\*** | BB-R-001.11 — `$expand` v0.1 only if V8 passes, else v0.2; BIGBOOK stack row; v0.3 Snowstorm = SNOMED only |
| D24 | A | ADR-003 options + decision; BB-R-014.5; v1.0 line |
| D25 | P | BB-R-016; v0.3 (webhook) |
| D26 | A | BB-R-006.14; ADR-001 |
| D27 | A | non-goals; BB-R-016 |
| D28 | P | BB-R-020 |
| D29 | P | BB-R-007.8, BB-R-025 |
| D30 | P | BB-R-001.10 |
| D31 | A / P | v0.1 KEEP list (BB-R-001.5/11), 16 drops (non-goals); v0.2 KEEP list (table) |
| D32 | A | BB-R-011.3 |
| D33 | A | BB-R-011.8; `lite` line |
| D34 | A | BB-R-005.7, BB-R-011.4 |
| D35 | A | BB-R-005.6 |
| D36 | A | BB-R-011.8, BB-R-023 |
| D37 | A | BB-R-007.12 |
| D38 | P | BB-R-014.3/4/6 row (CapabilityStatement customisation) |
| D39 | A | BB-R-011.8 |
| D40 | A | BB-R-011.8 |
| D41 | A | BB-R-011.2 |
| D42 | A | BB-R-004.8; ADR-003 consequences |
| D43 | A | BB-R-011.8, BB-R-014.6; ADR-004 |
| D44 | A | BB-R-002.4; **V9** |
| D45 | P | BB-R-002.7, BB-R-014.3 |
| D46 | P | BB-R-014.3/6; ADR-003 Open 2 |
| D47 | P | BB-R-023, BB-R-014.6 |
| D48 | A | BB-R-009.1 |
| D49 | A | ADR-003 proof cell; BB-R-014 exit test v0.2 |
| D50 | A | ADR-003 options; BB-R-014.5 |
| D51 | A | ADR-003 consequences (with D3) |
| D52 | A / P | §2a escape hatch v0.1; §9–13 → v0.2 table |
| D53 | A | ADR-002 decided none; BIGBOOK stack row |
| D54 | **A\*** | BB-R-007.3 — numbers pinned; no preamble/delivery split |
| D55 | A | BB-R-007.3; ADR-002; ARCHITECTURE §2(c) |
| D56 | **A\*** | BB-R-007.4 — byte-compatible `X-Signature` only; no timestamp variant |
| D57 | A | BB-R-007.10; ADR-001 hook table |
| D58 | A | BB-R-006.14; BB-R-016 |
| D59 | P | BB-R-016 |
| D60 | P | BB-R-007.13, BB-R-025 |
| D61 | P | BB-R-025; ADR-002 revisit trigger |
| D62 | A / P | non-goals; Batch → BB-R-020, Download → BB-R-009.5 v0.3 |
| D63 | A | BB-R-007.14 non-goal v0.1/v0.2; v0.3 candidate |

## Verify on the pinned HAPI container

| V | Question | Issue | Blocking |
|---|---|---|---|
| V1 | `SearchNarrowingInterceptor` fires for `_include`/`_revinclude` sub-queries; `GraphQLProvider` resolves through the DAO/interceptor chain | #7, #10 | **yes** |
| V2 | `_filter` operators and dotted paths | #9 | no |
| V3 | GraphQL connections and mutations | #10 | no |
| V4 | Runtime custom `SearchParameter` + `$reindex` | #9 | no |
| V5 | `InMemoryResourceMatcher` capability set | #7, #12 | no |
| V6 | `_count` cap, default `_total`, page-link shape | #9 | no |
| V7 | `/metadata` operation audit on a fresh `lite` | #8 | no |
| V8 | `$expand` on base R4 ValueSets; terminology content upload | #8 | no (decides BB-R-001.11's version) |
| V9 | `SUBSETTED` tagging on `_summary`/`_elements` (from D44) | #9 | no |

V9 is not a row in the area files; it is D44's "verify and pin" item promoted to the V list at reconcile time.
