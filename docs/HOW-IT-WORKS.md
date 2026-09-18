# How Big Book works

> Read this first. It is the plain-language layer over `docs/REQUIREMENTS.md`: eight things a person does with Big Book, in order, with the calls involved and the requirement each one exercises. It adds no requirements and makes no decisions. If this page and REQUIREMENTS.md disagree, REQUIREMENTS.md wins — then fix this page.
> Scope: v0.1 `lite`. Where a step says "v0.2", that is where it is going, not what ships first.

## What Big Book is, in one paragraph

Big Book is a backend that healthcare apps run on. You install it with one command and get a FHIR database, a login system, separation between organisations, rules about who may see what, and a way to react when data changes. Your app — a clinic system, a telemedicine service, a personal health assistant — talks to Big Book instead of building those five things itself. Medplum is the same idea in TypeScript; Big Book is the Java version, assembled from HAPI FHIR (the FHIR server, on Postgres), Keycloak (login) and a small amount of Big Book code that makes them behave as one product.

Three words that recur:

- **Project** — one organisation's private space. A clinic. Data in one project is invisible from another.
- **Membership** — a person's or an app's seat in a project: which Practitioner or Patient record is theirs, whether they're an admin, which access policy applies.
- **Profile** — the FHIR resource that represents the caller: a `Practitioner` for a doctor, a `Patient` for a patient, a `ClientApplication` for an app.

---

## 1. Install it

*Who:* anyone with Docker on a laptop. *Requirements:* BB-R-011.

```
curl -O https://raw.githubusercontent.com/KhondokerTanvirHossain/big-book-of-boo-boos/main/deploy/compose/lite.yml
docker compose -f lite.yml up -d
```

Three containers start: Postgres, Keycloak, and the Big Book server with HAPI FHIR inside it. Under ten minutes on a cold image cache. When `docker compose ps` shows all three healthy, `GET http://localhost:8080/fhir/R4/metadata` returns a CapabilityStatement — the FHIR way of saying "I'm up, here's what I can do."

On first boot Big Book creates one super-admin user from two environment variables. `BIGBOOK_ADMIN_EMAIL` is required. If `BIGBOOK_ADMIN_PASSWORD` is unset, Big Book generates one, prints it once in the startup log, and starts with it; there is no default password (BB-R-005.7). Starting a second time changes nothing.

Nothing else is needed for the rest of this page. An admin console (§8) is an optional add-on.

---

## 2. First login as the super-admin

*Who:* the person who installed it. *Requirements:* BB-R-004, BB-R-005.7.

The super-admin is a Keycloak user in a Keycloak realm called `bigbook`. Logging in is Keycloak's standard OAuth2 flow, wearing Big Book's URLs:

1. Your browser (or the SDK) goes to `https://bigbook.example/oauth2/authorize`. Big Book forwards it to Keycloak's login page.
2. Email + password. If TOTP is set up, a six-digit code.
3. Keycloak sends you back with an authorization code; the client swaps it at `/oauth2/token` for an **access token** — a signed JWT.

The token carries five claims Big Book cares about: `exp` and `client_id` (standard), `login_id` (a session id — the Medplum SDK refuses to believe it is talking to a Medplum-shaped server without it), `profile` (which FHIR resource is you), and `project` plus `membership` (which project this token is for, and your seat in it). Every call from now on sends `Authorization: Bearer <token>`. Big Book checks the signature against its own `/.well-known/jwks.json`, so nothing needs to phone Keycloak per request.

One call tells the caller who it is:

```
GET /auth/me
→ { profile:       { resourceType: "Practitioner", id: "…", name: [...] },        // the whole resource
    project:       { resourceType: "Project", id: "…", name: "…", features: [...], superAdmin: false },
    membership:    { resourceType: "ProjectMembership", id: "…", profile: {...}, admin: false },
    accessPolicy:  { resourceType: "AccessPolicy", resource: [...] },                    // compiled, parameters substituted
    config:        { resourceType: "UserConfiguration", menu: [...] } }
```

The SDK calls this once after login and caches it. It is the main endpoint Big Book writes itself in the auth area; the rest is Keycloak behind a Big Book path, with two small reshapes (`/.well-known/openid-configuration`, `/oauth2/logout`) and, from v0.2, a wrapper on `/oauth2/token` that adds `project` and `profile` to the response body for the Medplum SDK.

Apps that have no human — a nightly job, an integration — use **client credentials**: `POST /oauth2/token` with `client_id` + `client_secret`, no browser. Same token shape.

---

## 3. Create a project

*Who:* super-admin only. *Requirements:* BB-R-005.1, .6, .7.

```java
client.createProject(new Project().setName("Dhaka Family Clinic").setCheckReferencesOnWrite(true));
```

A project is created through the SDK's `createProject` (super-admin only; the HTTP spelling is fixed by issue #4). Three things happen in one step: a Keycloak **organisation** is created (this is what groups the project's users), a HAPI **partition** is created (this is what physically separates its data), and a `Project` record links the two. From here on every resource written into the project is stamped with it and every read is confined to it — a token for project A asking for a Patient in project B gets 404, not 403. The other project doesn't exist as far as that token is concerned.

A super-admin token normally acts inside the super-admin project. To act inside another project it either uses a route that names the project (`/admin/projects/{id}/…`) or adds an optional `X-Project: {id}` header on a FHIR call. Non-super-admins can't use the header (403).

Project settings that matter in v0.1: `checkReferencesOnWrite` (refuse a write that points at a resource that doesn't exist — on by default), `defaultProfile` (a FHIR profile applied to every resource that doesn't declare one), `features[]`, and `secret[]` (per-project secrets, stored plaintext at rest in `lite`; Vault in `full`).

---

## 4. Invite a user

*Who:* a project admin or super-admin. *Requirements:* BB-R-005.2–.4, BB-R-010, BB-R-006.

```
POST /admin/projects/{id}/invite
{ "resourceType": "Practitioner", "firstName": "Farhana", "lastName": "Rahman",
  "email": "farhana@clinic.bd",
  "membership": { "admin": false, "accessPolicy": { "reference": "AccessPolicy/practitioner-default" } },
  "mfaRequired": true, "sendEmail": true }
```

Big Book creates a `Practitioner` resource in the project (her profile), a `ProjectMembership` linking that profile to the project with the given policy, and a Keycloak user — or links an existing Keycloak user if the email is already known. Then Keycloak emails her a set-password link. If `mfaRequired` is true, her first login forces TOTP enrolment before she gets a token.

Inviting the same email into the same project twice gets 409. Inviting it into a second project creates a second membership: same person, different privileges per project. A doctor can be admin at one clinic and read-only at another.

`resourceType: "Patient"` invites a patient the same way; their profile is a `Patient` and they're scoped to that one project. `resourceType: "ClientApplication"` goes through `/admin/projects/{id}/client` instead and produces a machine identity with a `client_id` and `client_secret` — that's how an app like Baymax gets in.

**What a member may do** is decided by the `AccessPolicy` on their membership. A policy is a list of rules, each naming a resource type, an optional search-string filter, and what's allowed. The clearest case is a patient: invite one with `{ "resourceType": "Patient", "firstName": "Karim", "lastName": "Hossain", "email": "karim@example.bd", "membership": { "accessPolicy": { "reference": "AccessPolicy/patient-default" } } }` and give that policy:

```json
{ "resourceType": "AccessPolicy", "name": "patient-default",
  "resource": [
    { "resourceType": "Patient", "criteria": "Patient?_id=%patient.id", "readonly": true },
    { "resourceType": "Observation", "criteria": "Observation?subject=%patient", "hiddenFields": ["note"] }
  ] }
```

`%patient` is replaced per membership with that member's own `Patient` record (it defaults to the member's profile, so it means something only on a Patient or RelatedPerson membership — on a Practitioner it would point at the Practitioner and match nothing). Karim reads his own Patient record but can't change it, sees only Observations about himself, and never sees the `note` field. A write that would move a resource outside his filter is refused; a policy the server can't evaluate is refused at the moment it's saved, not silently ignored. `admin: true` on a membership does **not** switch policies off — it adds the right to manage users and settings on top of whatever policy applies. Only the super-admin bypasses everything.

---

## 5. A user logs in and works

*Who:* Farhana, through an app. *Requirements:* BB-R-004, BB-R-006.

Same flow as §2: the app sends her to `/oauth2/authorize`, she authenticates with Keycloak (password, then TOTP since she was invited with `mfaRequired`), the app gets a token whose `profile` is her `Practitioner`. `/auth/me` tells the app which project she's in and returns her `UserConfiguration` — the menu the admin console shows her.

From then on every request runs through the same gate in this order: is the token valid → which partition (project) → which membership → which policy → does the policy allow this interaction on this resource type → filter the search to what the policy allows → strip hidden fields from the response → after a write, check the result is still inside the policy. A denial is 403 and is logged with project, user, resource and interaction.

If she belongs to two projects, she picks one at login (the mechanism — Keycloak organisation selection — is fixed by issue #5); the token is for that project. Switching means a new token.

---

## 6. An app stores and reads patient data

*Who:* Baymax, as a `ClientApplication`. *Requirements:* BB-R-001, BB-R-002, BB-R-012. This is the v0.1 exit criterion.

Through the Java SDK:

```java
BigBookClient client = BigBookClient.builder()
    .baseUrl("https://bigbook.example")
    .clientCredentials(clientId, clientSecret)
    .build();

Patient p = client.create(new Patient()
    .addName(new HumanName().setFamily("Rahman").addGiven("Farhana"))
    .setBirthDate(new Date(...)));

Patient same = client.read(Patient.class, p.getIdElement().getIdPart());

Bundle page = client.search(Patient.class, "name=Rahman&_sort=-_lastUpdated&_count=20");
for (Patient hit : client.searchResources(Patient.class, "birthdate=ge1990-01-01")) { ... }

Observation o = client.create(new Observation()
    .setSubject(new Reference(p))
    .setStatus(Observation.ObservationStatus.FINAL)
    .setCode(new CodeableConcept().addCoding(new Coding("http://loinc.org", "29463-7", "Body weight")))
    .setValue(new Quantity(72).setUnit("kg")));
```

What that is on the wire — the SDK is thin, and any HTTP client can do the same:

| Action | HTTP |
|---|---|
| store | `POST /fhir/R4/Patient` → 201, server-assigned UUID id |
| read | `GET /fhir/R4/Patient/{id}` → 200 with `ETag` |
| update | `PUT /fhir/R4/Patient/{id}` with `If-Match` → new version; old one kept |
| history | `GET /fhir/R4/Patient/{id}/_history` |
| search | `GET /fhir/R4/Patient?name=Rahman&_count=20` → Bundle with a `next` link |
| chained search | `GET /fhir/R4/Observation?subject.name=Rahman&_include=Observation:subject` |
| delete | `DELETE /fhir/R4/Patient/{id}` → later reads give 410 Gone |
| several at once | `POST /fhir/R4` with a `transaction` Bundle — all or nothing |
| validate first | `POST /fhir/R4/Patient/$validate` |
| GraphQL | `POST /fhir/R4/$graphql` |

All of it is HAPI FHIR; Big Book adds the partition confinement and the policy gate. Every write stamps the project; every read is confined to it. A client that sends `X-Medplum: extended` (the Medplum SDK does, by default) sees that stamp as `meta.project`, plus `meta.author` and `meta.compartment`, on every resource it reads; without the header those three fields are absent (BB-R-014.3, v0.2). Referential integrity is on: an Observation pointing at a Patient that doesn't exist — or exists in another project — is refused.

Files (a PDF, an image) go in as `Binary`: `POST /fhir/R4/Binary` with the raw bytes and a `Content-Type`, referenced from a `DocumentReference`. In `lite` they live in Postgres; in `full`, in MinIO.

---

## 7. Something happens when data changes

*Who:* an integrator; in `full`, an n8n workflow. *Requirements:* BB-R-007, BB-R-008.

A `Subscription` is a standing instruction: "when a resource matching this search is created, updated or deleted, POST it here."

```json
{ "resourceType": "Subscription", "status": "active",
  "criteria": "Patient?",
  "channel": { "type": "rest-hook", "endpoint": "https://n8n.example/webhook/new-patient", "payload": "application/fhir+json" },
  "extension": [
    { "url": "https://medplum.com/fhir/StructureDefinition/subscription-supported-interaction", "valueCode": "create" },
    { "url": "https://www.medplum.com/fhir/StructureDefinition/subscription-secret", "valueString": "…" } ] }
```

When Baymax creates a Patient, within five seconds the endpoint receives a POST with the Patient as the body and an `X-Signature` header — an HMAC of the body with the secret — so the receiver can trust it. The subscription above fires on create only, not on update. It fires only for resources in its own project, and only for resources its author's access policy would let them read.

Delivery is durable: pending deliveries live in a Postgres table, so a restart doesn't lose them. A failed POST gets four attempts in total — three retries, at 20 s, 40 s and 80 s (the delay doubles each time and is capped at 8 h). Every attempt writes an `AuditEvent`, so "did it go out?" is a search: `GET /fhir/R4/AuditEvent?entity=Subscription/{id}&_sort=-date`. `POST /fhir/R4/Patient/{id}/$resend` fires it again on demand (project admins only).

The shipped example: `examples/n8n/patient-welcome-email.json` — Subscription → n8n webhook → send an email → write a `Communication` back through `/fhir/R4` with a `ClientApplication` token.

Bots — the same idea but as Java code inside the server, on a Camel route — are v0.2.

---

## 8. An admin looks at it

*Who:* a project admin, without writing code. *Requirements:* BB-R-013.

```
docker compose -f lite.yml -f admin.yml up -d
```

adds Appsmith, a low-code tool, with a pre-built Big Book admin app imported at first boot — or, if Appsmith's import API turns out not to allow that (ADR-004 open item 3), one documented manual import. Eleven screens (`app/lowcode/SCREENS.md`): sign in, pick a project, browse any resource type with search and paging, open one and edit its JSON, see its history, project settings and secrets, users and their policies, invite, patients, clients, an AccessPolicy editor that validates against the schema, and a subscription list with last-delivery status.

In v0.1 the console signs in with Appsmith accounts and talks to Big Book as one super-admin `ClientApplication`; per-user Keycloak login and "which human clicked this" attribution arrive in v0.2. A proper admin UI built into the server is v0.3.

---

## Non-functional requirements (v0.1 — proposed, to be blessed)

Nothing below is in REQUIREMENTS.md yet. Numbers are proposals for a laptop `lite` install and become BB-R-029 once blessed (BB-R-028 is the HL7v2 agent).

| Concern | v0.1 target | Measured how |
|---|---|---|
| Install | ≤ 10 min cold, 3 containers | CI timer (BB-R-011) |
| Footprint | `lite` idles under 3 GB RAM total, runs on 2 vCPU | `docker stats` after 5 min idle |
| Read latency | `GET Patient/{id}` p95 < 100 ms at 100k Patients, 1M Observations | k6 script in CI, seeded dataset |
| Search latency | simple search p95 < 300 ms at the same dataset | same |
| Write latency | single create p95 < 200 ms | same |
| Concurrency | 50 concurrent SDK clients without errors | k6 |
| Subscription delivery | matched → POST sent, p95 < 5 s | exit test BB-R-007 |
| Restart safety | no lost writes, no lost pending deliveries across `docker compose restart` | exit test BB-R-007.3 |
| Data safety | Postgres is the only state; `pg_dump` is a full backup | documented, BB-R-011 |
| Auth | invalid or expired token rejected with 401 in < 10 ms, no DB hit (a valid token still costs one membership lookup) | unit test |
| Logs | every request logs request id, project id, user id (JSON) | exit test BB-R-015 |

Not v0.1: horizontal scaling, HA Postgres, multi-node subscriptions (single JVM by ADR-002/006), audit compliance profiles (BALP, v0.2), rate limits (v0.2), backup/DR guides (v0.3).

---

## What is deliberately not here

Big Book stores clinical data; it doesn't know what to do with it. There is no appointment screen, no prescription flow, no chart, no note template. Those belong to the app on top — Niramoy, Baymax, or yours. Medplum's `medplum-provider` is that app in their world; in ours it doesn't exist yet and isn't planned before v1.0.

Full list: `docs/REQUIREMENTS.md` → Non-goals.