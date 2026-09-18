# ADR-006 HAPI deployment topology

Status: decided (2026-09-12)
Decision: **Embed.** HAPI FHIR JPA runs inside the Big Book Spring Boot JVM. `lite` = three containers: Postgres, Keycloak, Big Book. No separate HAPI image; `hapi-fhir-jpaserver-starter` is not used as a runtime component.
Context: raised by the Architect while drafting `docs/ARCHITECTURE.md`. BIGBOOK.md's repo layout said "Spring Boot app embedding HAPI JPA" while BIGBOOK.md `lite`, BB-R-011.1, ADR-001 and ADR-004 said "four containers" including a HAPI container. Incompatible.

## Rationale
- ADR-001's enforcement path (AuthorizationInterceptor, SearchNarrowingInterceptor, STORAGE_PRESHOW_RESOURCES, STORAGE_PRESTORAGE_RESOURCE_UPDATED, subscription hooks) requires Big Book code in HAPI's JVM. A separate HAPI container would mean shipping a custom HAPI image carrying Big Book's interceptors — the same coupling with worse packaging.
- ADR-004's v0.3 Vaadin option assumes the same JVM.
- It is what the repo layout already stated.
- One fewer image on the 10-minute timer.

## Consequences
- "four containers" → "three" in BIGBOOK.md (`lite` principle, stack table), BB-R-011.1, ADR-001, ADR-004.
- HAPI upgrades are a Big Book build and release, not a `docker pull`. HAPI version pinned in the single versions file; upgrade cadence is the Big Book release cadence (BIGBOOK.md "Pin everything").
- First instance of that cost (issue #2, HAPI 8.12.1): HAPI's BOM pins Testcontainers 2.x while Spring Boot's pins the 1.x modules; Gradle resolved the higher core against the lower modules and the tests failed to link until the 2.x module names were used. Expect the same class of BOM disagreement on every HAPI upgrade.
- HAPI and Big Book tables share one database (`bigbook`, schemas `hapi` and `bigbook`; Keycloak has its own) so the subscription delivery table (ARCHITECTURE.md §2(c)) and HAPI writes can share a transaction.
- `full` profile unchanged; `admin` overlay unchanged.
- Reuse-first is not weakened: HAPI is still the FHIR server, consumed as a library rather than an image.

## Reversal cost
Medium (~1 week, no API change): split into a custom HAPI image carrying the interceptors plus a separate admin JVM. Trigger: HAPI upgrade friction becomes worse than the packaging cost, or a deployment demands independent scaling of the FHIR tier.
