# Big Book admin UI — v0.1 screen inventory (ADR-004 §4)

Build list for issue #16. Every `/fhir/R4` call carries the super-admin `ClientApplication` token and `X-Project: <id>` (BB-R-005.7). A screen calls only the endpoints listed here.

| # | Screen | BB-R-013 | Calls |
|---|---|---|---|
| S1 | Sign-in + project switcher | 13.1 | Appsmith account login; `POST /oauth2/token` (client_credentials); `GET /admin/projects` |
| S2 | Resource browser — list | 13.2 | `GET /fhir/R4/metadata` (type list); `GET /fhir/R4/{Type}?{params}&_count=20&_offset=n&_sort=-_lastUpdated` |
| S3 | Resource detail / JSON edit | 13.2 | `GET /fhir/R4/{Type}/{id}`; `POST /fhir/R4/{Type}/$validate`; `PUT` with `If-Match`; `POST /fhir/R4/{Type}` (new); `DELETE` |
| S4 | Resource history | 13.2 | `GET /fhir/R4/{Type}/{id}/_history`; `GET …/_history/{vid}` |
| S5 | Project details / settings / secrets | 13.3 | `GET /admin/projects/{id}`; `PUT /admin/projects/{id}` (`setting[]`, `secret[]`, `features[]`, `checkReferencesOnWrite`, `defaultProfile`) |
| S6 | Users | 13.3 | `GET /admin/projects/{id}/members`; `PUT /admin/projects/{id}/members/{mid}` (`admin`, `accessPolicy[]`) |
| S7 | Invite | 13.3 | `POST /admin/projects/{id}/invite` |
| S8 | Patients | 13.3 | `GET /fhir/R4/Patient?_sort=-_lastUpdated`; `GET /admin/projects/{id}/members?profileType=Patient` |
| S9 | Clients | 13.3 | `GET /admin/projects/{id}/members?profileType=ClientApplication`; `POST /admin/projects/{id}/client` — secret rendered once from the response, never re-fetched |
| S10 | AccessPolicy editor + assign | 13.4 | `GET /fhir/R4/AccessPolicy`; `POST`/`PUT /fhir/R4/AccessPolicy/{id}`; schema check in the custom widget (ajv over the vendored Medplum AccessPolicy JSON schema in `app/lowcode/schema/`); assign via S6 `PUT` |
| S11 | Subscriptions | 13.5 | `GET /fhir/R4/Subscription`; `PUT` (status active/off); last delivery: `GET /fhir/R4/AuditEvent?entity=Subscription/{id}&_sort=-date&_count=1` (BB-R-007.3) |

Out of scope for v0.1: per-user OIDC sign-in, human attribution on writes (v0.2, BB-R-024), Bots page (v0.2), timeline/chart/questionnaire views (non-goal).
