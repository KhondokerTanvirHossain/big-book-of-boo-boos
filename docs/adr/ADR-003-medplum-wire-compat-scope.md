# ADR-003 Medplum wire-compatibility scope

Status: open
Decision: —
Context: see `docs/BIGBOOK.md` → Open decisions; requirements blocked on this: BB-R-004, BB-R-005, BB-R-013, BB-R-014

## Options

- FHIR + OAuth endpoints only
- FHIR + OAuth + `/auth/me` + admin routes + Medplum resource types (TS SDK / React app run unmodified)
