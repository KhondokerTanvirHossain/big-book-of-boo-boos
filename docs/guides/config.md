# Configuration reference

Big Book is configured by environment only (BB-R-011.3). There is no config file to write. Precedence is Spring Boot's own: environment variable, then the default in `application.yml`. With compose, a `.env` beside the compose file is how you set the environment.

**This page is enforced.** `ConfigDocTest` fails the build when a `bigbook.*` property or a `${VARIABLE}` in `deploy/compose/` has no row here. A key is added to this page in the same PR that adds the key.

## `lite` keys

All optional. `lite` starts with none of them set.

| Env key | Spring property | Default | Profile | What it is |
|---|---|---|---|---|
| `BIGBOOK_BASE_URL` | `bigbook.base-url` | `http://localhost:8080/` | `lite`, `full` | Public origin of the server, with trailing slash. Base of every link the FHIR API emits; the FHIR base is `<base-url>fhir/R4`. |
| `POSTGRES_PASSWORD` | `spring.datasource.password` | generated on first boot | `lite` | Password of the `bigbook` Postgres role, used by both Big Book and Keycloak. **First-boot input**: read once, when the database is created. |
| `KEYCLOAK_ADMIN_PASSWORD` | none (Keycloak's `KC_BOOTSTRAP_ADMIN_PASSWORD`) | generated on first boot | `lite` | Password of Keycloak's first admin user, `admin`. **First-boot input**: read once, when Keycloak has no admin yet. |

### How the secrets travel

No secret has a default anywhere in the repository (`deploy/ci/check-deploy.sh` checks). In `lite`, the Postgres container writes each secret once as a file into the `secrets` volume, mounted at `/run/bigbook/` in all three containers and read-only in two of them: `postgres-password` and `keycloak-admin-password`. The value is what you supplied in `.env`, or 32 random characters if you supplied nothing. Big Book reads the directory through Spring's `configtree` import; Keycloak reads the files at start.

The files are readable by anything that can mount that volume, which is the three `lite` containers. That is the `lite` trade-off; `full` takes secrets from Vault.

To rotate the Postgres password: `ALTER ROLE bigbook PASSWORD '…'` in `psql`, write the same value into `/run/bigbook/postgres-password`, restart `keycloak` and `bigbook`.

## Server properties with working defaults

Set these only when running the server outside `lite.yml`.

| Env key | Spring property | Default | Profile | What it is |
|---|---|---|---|---|
| `SPRING_DATASOURCE_URL` | `spring.datasource.url` | `jdbc:postgresql://postgres:5432/bigbook?currentSchema=hapi` | all | HAPI's tables live in schema `hapi` of database `bigbook`. Keep `currentSchema=hapi`. |
| `SPRING_DATASOURCE_USERNAME` | `spring.datasource.username` | `bigbook` | all | Postgres role. |
| `SPRING_DATASOURCE_PASSWORD` | `spring.datasource.password` | none; the server will not start without one | all | Overrides the `/run/bigbook/postgres-password` file. |
| `SERVER_PORT` | `server.port` | `8080` | all | HTTP port inside the container. |

## Version pins

These are not configuration. They live in [`deploy/versions.env`](../../deploy/versions.env), the one file where upstream versions are pinned (BB-R-011.6), and are bumped only there. The compose files default to the same values so that a downloaded `lite.yml` works alone; `deploy/ci/check-deploy.sh` fails on any drift between the two.

| Key | Pins | Read by |
|---|---|---|
| `POSTGRES_VERSION` | `postgres` image tag | compose, the server's integration test |
| `KEYCLOAK_VERSION` | `quay.io/keycloak/keycloak` image tag | compose |
| `TEMURIN_VERSION` | `eclipse-temurin` base image of the server image | `server/Dockerfile`, `deploy/compose/build.yml` |
| `HAPI_FHIR_VERSION` | HAPI FHIR, embedded as a library (ADR-006) | Gradle |
| `SPRING_BOOT_VERSION` | Spring Boot. Follows the Boot line HAPI is built against; move the two together. | Gradle |
| `BIGBOOK_VERSION` | Big Book's own version: Gradle project version and server image tag | Gradle, compose |
| `BIGBOOK_IMAGE` | Server image repository, default `ghcr.io/khondokertanvirhossain/bigbook-server`. Not in `versions.env`; an override for CI and for local builds. | compose |
