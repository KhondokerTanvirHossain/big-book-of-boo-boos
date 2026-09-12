# Big Book — repo brief

> Canonical context for this repo. Read by Claude Code, the *Big Book – PO* project, and the *Big Book – Architect* project. If code and this file disagree, code wins — then fix this file.

## What it is

Big Book is an open-source, Java/Spring healthcare application platform with the same shape as Medplum: FHIR store + identity + access policies + subscriptions + bots + SDK + admin UI, deployable with one command.

It is a **distribution, not a rewrite**. Every slot is filled by a battle-tested third-party component; Big Book contributes the glue, the opinions, the packaging, and the developer experience. Think Bahmni over OpenMRS, not a fork.

Reference product: Medplum (Apache 2.0). We match its concepts and, where cheap, its wire format. We do not port its code.

License: Apache 2.0. Owner: Tanvir (personal/Elio project). First production users: Niramoy and Baymax — they consume Big Book as a dependency; nothing Niramoy-specific lives in this repo.

## Why it should exist

- Java healthcare teams have HAPI (a store) but nothing that gives them auth, tenancy, policies, bots, and a UI out of the box. Medplum solved that for TypeScript.
- "Drop-in Medplum for the JVM, `helm install` and go" is the one-line pitch.
- Secondary goals: a Bangladesh FHIR profile set, and a resume-grade OSS project with real users.

## Stack (one component per slot)

| Slot | Component | Status |
|---|---|---|
| FHIR store, search, GraphQL, bulk, Subscriptions | HAPI FHIR JPA | decided |
| Full-text search | HAPI + OpenSearch | decided, `full` profile only |
| Identity, OIDC/OAuth2, users, MFA | Keycloak | decided |
| SMART-on-FHIR scopes | Keycloak extension (pick a maintained one) | open |
| Access policies | Medplum-shaped `AccessPolicy` → HAPI `AuthorizationInterceptor` + `SearchNarrowingInterceptor` rules, Big Book hooks; no external engine | decided |
| Multi-tenancy | HAPI partitioning keyed on Keycloak organisation | decided |
| Consent | HAPI `ConsentInterceptor` | decided |
| Bots (dev-authored, in-JVM) | Apache Camel routes on Spring Boot | decided |
| Automations (ops-authored, low-code) | n8n via Subscription rest-hooks | decided |
| Durable workflows | Temporal / Camunda 8 | deferred — not before v0.3 |
| Event bus | Kafka or RabbitMQ | open |
| Binary storage | MinIO (S3 API) | decided |
| Terminology | HAPI terminology + Snowstorm | Snowstorm `full` only |
| Validation / IGs | HAPI validator; BD IG authored in FSH | decided |
| HL7v2 / agent | HAPI HL7v2 + Camel HL7 | v0.3 |
| Notifications | Spring Mail; Novu if channels grow | decided |
| Admin UI | Appsmith CE overlay (v0.1); Vaadin Flow (v0.3) | decided |
| Gateway / TLS | Traefik | decided |
| Observability | OpenTelemetry → Grafana stack | decided |
| Secrets | Vault | decided |
| Packaging | Helm chart + `docker compose` | decided |

## What we write (and nothing else)

1. **Tenant model** — Keycloak org ↔ HAPI partition mapping, project membership, invitations.
2. **Policy adapter** — Medplum-shaped `AccessPolicy` JSON → HAPI interceptor rules + hooks (ADR-001).
3. **Bot SDK** — Spring Boot starter so a bot is one annotated class on a Camel route.
4. **Java client SDK** — auth flows and typed conveniences over the HAPI generic client.
5. **Packaging** — Helm chart, compose file, profiles (`lite`, `full`), install docs.

Target: 5–10k lines. If a feature needs more than that, the answer is a third-party component, not code.

## Non-goals

- Reimplementing any FHIR server function HAPI already has.
- A custom auth server, policy engine, workflow engine, or UI framework.
- Niramoy/Baymax-specific features. Those live in their own repos.
- Feature parity with Medplum in v0.x. Parity is a v1 question.

## Principles

- **Reuse first.** Before writing code, prove no maintained component does it.
- **`lite` must work in 10 minutes on a laptop.** Postgres + HAPI + Keycloak + Big Book, nothing else. Everything in `full` is optional and must degrade cleanly.
- **Wire-compatibility is a growth hack.** Match Medplum's FHIR and OAuth endpoints where it costs nothing; never match its internals.
- **Pin everything.** One upgrade cadence for all upstreams; e2e smoke test runs the full stack in CI.
- **Docs live here.** ADRs in `docs/adr/`, decisions in this file. No context outside the repo.
- **Dogfood.** Niramoy or Baymax runs each release before it's tagged.

## Repo layout

```
core/       tenant model, policy adapter, shared types
server/     Spring Boot app embedding HAPI JPA + admin API
bots/       bot runtime + starter
client/     Java SDK
cli/        picocli
agent/      HL7v2 bridge (v0.3)
app/        admin UI (v0.2+)
deploy/     helm/, compose/
docs/       BIGBOOK.md (this), adr/, guides/
examples/
```

## Roadmap

| Version | Scope | Exit criterion |
|---|---|---|
| v0.1 | `lite` profile: store, Keycloak auth, tenancy, access policies, subscriptions, Java SDK, low-code admin | Baymax reads/writes patient records through it |
| v0.2 | Bot SDK, CLI, audit events, bulk export, `full` profile | Niramoy prescription module writes `MedicationRequest` via Big Book |
| v0.3 | HL7v2 agent, terminology (Snowstorm), BD IG v1, proper admin UI | External pilot user outside Niramoy |
| v1.0 | Medplum wire-compat verified; docs site; 3+ external deployments | — |

Start date: after Brain Plus go-live (1 Jan). v0.1 target: 3 months from start.

## Open decisions (resolve via ADR)

- ADR-001 Policy engine — decided: no external engine; `AccessPolicy` translated to HAPI interceptor rules + Big Book hooks (Cerbos/OPA rejected).
- ADR-002 Event bus: Kafka vs RabbitMQ vs none in `lite`.
- ADR-003 Medplum wire-compatibility scope — decided: B in v0.1, SDK-grade C in v0.2, app-grade C v1.0.
- ADR-004 Admin UI path — decided: Appsmith CE overlay v0.1 (service-account); Vaadin Flow v0.3 (issue #1 closed the `@medplum/app` path).
- ADR-005 SMART-on-FHIR Keycloak extension.

## How the three surfaces work

- **Claude Code** does the technical work and owns `docs/`. Every change that touches a decision updates the relevant ADR or this file in the same commit.
- **Big Book – PO** (claude.ai project) owns scope, roadmap, issues, README/positioning, release notes. Reads this repo via project knowledge. Writes no code.
- **Big Book – Architect** (claude.ai project) is a sounding board for component choices and integration boundaries; reviews ADRs and large diffs on request. Holds no state of its own.

No reconciliation loop. The repo is the state.

## Working with Tanvir

Terse. Single-word approvals ("bless", "go", "confirmed"). Numbered answers to numbered questions. Wants a strong recommendation with rationale, not option lists. Proceeds as approval. Flags contradictions instead of silently building to the last message.
