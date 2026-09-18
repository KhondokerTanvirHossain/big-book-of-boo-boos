# Big Book

**A Medplum-shaped healthcare backend for the JVM.** FHIR store, login, multi-tenancy, access policies, subscriptions and a Java SDK — installed with one command, built from HAPI FHIR and Keycloak, held together by a few thousand lines of Spring.

> Status: `lite` boots from source (PR #23); the published image and the two-command install land with the v0.1.0 tag. If you're here to help, see [Contributing](#contributing).

## What it is

A backend that healthcare apps run on. Not an EHR. You install Big Book and get:

- a **FHIR R4 store** with search, history, transactions, GraphQL and validation — HAPI FHIR JPA, embedded
- **login** — OIDC, MFA, external identity providers — Keycloak
- **projects** — hard isolation between organisations, with users, memberships and invitations
- **access policies** — Medplum-shaped JSON rules over resource types, search criteria and fields
- **subscriptions** — durable rest-hooks with signatures and retries, and an n8n recipe for low-code automation
- a **Java SDK** and a Spring Boot starter
- a low-code **admin console**

Your app — a clinic system, a telemedicine service, a personal health assistant — talks to Big Book instead of building those itself. [How it works](docs/HOW-IT-WORKS.md) walks through all of it in eight steps.

## Why

Java healthcare teams have HAPI FHIR — an excellent store — and nothing above it. Auth, tenancy, policies, automation and an SDK are rebuilt per project. Medplum solved this for TypeScript. Big Book is the same shape for the JVM, and it matches Medplum's FHIR and OAuth endpoints where that costs nothing, so Medplum's docs and client examples largely apply.

**It is a distribution, not a rewrite.** Every slot is filled by a component that already exists; Big Book contributes the tenant model, the policy adapter, the SDK and the packaging. The custom-code budget is 5–10k lines and it is enforced.

**Compared to**

| | HAPI FHIR alone | OpenMRS / Bahmni | Medplum | Big Book |
|---|---|---|---|---|
| FHIR-native core | yes | no (FHIR as an API layer) | yes | yes |
| Auth, tenancy, policies out of the box | no | partly, own model | yes | yes |
| A complete EHR you can deploy today | no | **yes** | no (sample app) | no |
| Language | Java | Java | TypeScript | Java |
| Licence | Apache 2.0 | MPL 2.0 / AGPL | Apache 2.0 | Apache 2.0 |

If you need a hospital system this quarter, deploy Bahmni. If you're building a FHIR-native product on the JVM and want the platform layer done, that's Big Book.

## Install (v0.1, when it ships)

```
curl -O https://raw.githubusercontent.com/KhondokerTanvirHossain/big-book-of-boo-boos/main/deploy/compose/lite.yml
docker compose -f lite.yml up -d
```

Three containers — Postgres, Keycloak, Big Book — in under ten minutes on a laptop. That ten-minute number is a requirement, not a hope; anything that threatens it is rejected.

## Use (Java SDK)

```java
BigBookClient client = BigBookClient.builder()
    .baseUrl("http://localhost:8080")
    .clientCredentials(clientId, clientSecret)
    .build();

Patient p = client.create(new Patient().addName(new HumanName().setFamily("Rahman")));
Bundle hits = client.search(Patient.class, "name=Rahman");
```

Everything the SDK does is plain FHIR REST underneath: `POST /fhir/R4/Patient`, `GET /fhir/R4/Patient?name=Rahman`.

## Roadmap

| Version | Scope | Done when |
|---|---|---|
| v0.1 | `lite` profile: store, auth, projects, policies, subscriptions, SDK, admin console, performance floor | a real app reads and writes patients through the SDK on a laptop install |
| v0.2 | Bot SDK (Camel), CLI, audit events, bulk export, `full` profile, Medplum SDK-grade compatibility | a prescription module writes `MedicationRequest` through it |
| v0.3 | HL7v2 agent, terminology (Snowstorm), Bangladesh FHIR IG, built-in admin UI | first external pilot |
| v1.0 | Medplum app-grade compatibility, docs site, 3+ external deployments | — |

## Non-goals

Clinical features — charting, scheduling, orders, prescribing, billing — belong to the app on top, not here. So do US-specific integrations, DICOM, CDS Hooks, a React component library (before v1.0), and any rewrite of what HAPI or Keycloak already do. The full list is in [REQUIREMENTS.md](docs/REQUIREMENTS.md#non-goals-v0x).

## Documentation

| Read this if you want to… | File |
|---|---|
| understand what it does, in plain language | [docs/HOW-IT-WORKS.md](docs/HOW-IT-WORKS.md) |
| know the rules of the project | [docs/BIGBOOK.md](docs/BIGBOOK.md) |
| see exactly what v0.1 must do, brick by brick | [docs/REQUIREMENTS.md](docs/REQUIREMENTS.md) |
| see how the pieces fit | [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) |
| know why a decision was made | [docs/adr/](docs/adr/) |
| see where Big Book deliberately differs from Medplum | [docs/guides/medplum-parity.md](docs/guides/medplum-parity.md) |
| see what Medplum actually does, line by line | [docs/inventory/](docs/inventory/) (frozen audit) |

## Contributing

Issues are the spec. Every one has a `## Task` and `## Acceptance criteria`; if you can close the acceptance criteria, the PR merges. Start with [`good first issue`](../../labels/good%20first%20issue). Read [CONTRIBUTING.md](CONTRIBUTING.md) first — it's short and the line-count rule matters.

## Licence

Apache 2.0. Big Book depends on HAPI FHIR (Apache 2.0), Keycloak (Apache 2.0), Appsmith CE (Apache 2.0) and Postgres. No copyleft component ships in the default profiles.

---

*Big Book of Boo-Boos — a personal open-source project by [Tanvir Hossain](https://github.com/KhondokerTanvirHossain). First users: Baymax and Niramoy, Dhaka.*
