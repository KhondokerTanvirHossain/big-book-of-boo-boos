# Configuration reference

Big Book is configured by environment only (BB-R-011.3). There is no config file to write. Precedence is Spring Boot's own: environment variable, then the default in `application.yml`. With compose, a `.env` beside the compose file is how you set the environment.

**This page is enforced.** `ConfigDocTest` fails the build when a `bigbook.*` property or a `${VARIABLE}` in `deploy/compose/` has no row here. A key is added to this page in the same PR that adds the key.

## `lite` keys

One is required: `BIGBOOK_ADMIN_EMAIL`. The rest are optional.

| Env key | Spring property | Default | Profile | What it is |
|---|---|---|---|---|
| `BIGBOOK_ADMIN_EMAIL` | `bigbook.admin.email` | **none, required** | all | Email, and login name, of the super-admin created on first boot (BB-R-005.7). The server refuses to start without it and names the variable in its log. |
| `BIGBOOK_ADMIN_PASSWORD` | `bigbook.admin.password` | generated, logged once | all | Password of that super-admin. **First-boot input**: used only when the user is created. Unset: 32 random characters are generated and written to the server log once (`docker compose -f lite.yml logs bigbook \| grep "Generated super-admin password"`), and stored nowhere by Big Book. `medplum_admin`, Medplum's default, is refused. |
| `BIGBOOK_BASE_URL` | `bigbook.base-url` | `http://localhost:8080/` | `lite`, `full` | Public origin of the server, with trailing slash. Base of every link the FHIR API emits; the FHIR base is `<base-url>fhir/R4`. |
| `POSTGRES_PASSWORD` | `spring.datasource.password` | generated on first boot | `lite` | Password of the `bigbook` Postgres role, used by both Big Book and Keycloak. **First-boot input**: read once, when the database is created. |
| `KEYCLOAK_ADMIN_PASSWORD` | none (Keycloak's `KC_BOOTSTRAP_ADMIN_PASSWORD`) | generated on first boot | `lite` | Password of Keycloak's first admin user, `admin`. **First-boot input**: read once, when Keycloak has no admin yet. |

### Ports, and where Keycloak is

`lite` publishes **only Big Book's port**, 8080. Keycloak's port is bound to `127.0.0.1:9080` and is not published to the network: the admin console is an operator-only address (`KC_HOSTNAME_ADMIN`, BB-R-011.9). Both are fixed in `deploy/compose/lite.yml`, not configurable by an environment variable.

Browsers never need Keycloak's port. With Keycloak's `hostname` pinned to `BIGBOOK_BASE_URL`, its login pages and theme assets are served through Big Book's own origin under `/realms/<realm>/**` and `/resources/**`, and every other path on that origin is 404 (ADR-003 allow-list, issue #5).

### How the secrets travel

No secret has a default anywhere in the repository (`deploy/ci/check-deploy.sh` checks). In `lite`, the Postgres container writes each secret once as a file into the `secrets` volume, mounted at `/run/bigbook/` in all three containers and read-only in two of them: `postgres-password`, `keycloak-admin-password` and `keycloak-client-secret`. The value is what you supplied in `.env`, or 32 random characters if you supplied nothing (the client secret is always generated). Big Book reads the directory through Spring's `configtree` import; Keycloak reads the files at start.

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
| `BIGBOOK_KEYCLOAK_URL` | `bigbook.keycloak.url` | `http://keycloak:8080` | all | Keycloak as the server reaches it (not the public URL). |
| `BIGBOOK_KEYCLOAK_CLIENT_ID` | `bigbook.keycloak.client-id` | `bigbook-server` | all | The confidential client whose service account administers realm `bigbook`. Defined in [`deploy/keycloak/bigbook-realm.json`](../../deploy/keycloak/bigbook-realm.json). |
| `BIGBOOK_KEYCLOAK_CLIENT_SECRET` | `bigbook.keycloak.client-secret` | none; the server will not start without one | all | Secret of that client. In `lite` it is generated on first boot, never supplied: file `/run/bigbook/keycloak-client-secret`, which Keycloak's realm import and the server both read. |

## Version pins

These are not configuration. They live in [`deploy/versions.env`](../../deploy/versions.env), the one file where upstream versions are pinned (BB-R-011.6), and are bumped only there. The compose files default to the same values so that a downloaded `lite.yml` works alone; `deploy/ci/check-deploy.sh` fails on any drift between the two.

| Key | Pins | Read by |
|---|---|---|
| `POSTGRES_VERSION` | `postgres` image tag | compose, the server's integration test |
| `KEYCLOAK_VERSION` | `quay.io/keycloak/keycloak` image tag | compose |
| `TEMURIN_VERSION` | `eclipse-temurin` base image of the server image | `server/Dockerfile`, `deploy/compose/build.yml` |
| `KEYCLOAK_ADMIN_CLIENT_VERSION` | `org.keycloak:keycloak-admin-client`, the Admin REST library. Its 26.0.x line serves every Keycloak 26.x server; it does not track `KEYCLOAK_VERSION`. | Gradle |
| `HAPI_FHIR_VERSION` | HAPI FHIR, embedded as a library (ADR-006) | Gradle |
| `SPRING_BOOT_VERSION` | Spring Boot. Follows the Boot line HAPI is built against; move the two together. | Gradle |
| `BIGBOOK_VERSION` | Big Book's own version: Gradle project version and server image tag | Gradle, compose |
| `BIGBOOK_IMAGE` | Server image repository, default `ghcr.io/khondokertanvirhossain/bigbook-server`. Not in `versions.env`; an override for CI and for local builds. | compose |
