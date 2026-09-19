# Install Big Book (`lite`)

`lite` is three containers: Postgres, Keycloak and the Big Book server, which has HAPI FHIR inside it (ADR-006). Under ten minutes on a cold image cache; CI measures that on every change (BB-R-011.1).

## You need

- **Docker Compose ≥ 2.23.1** (`docker compose version`). `lite.yml` carries its init scripts inline, which older Compose cannot read.
- **JDK 21**, only while you build the server image from source, which is the case until the v0.1.0 tag publishes it (see the note under Install).
- About 3 GB of free memory, and ports **8080** and **8081** free.

## Install

```
curl -O https://raw.githubusercontent.com/KhondokerTanvirHossain/big-book-of-boo-boos/main/deploy/compose/lite.yml
echo "BIGBOOK_ADMIN_EMAIL=you@example.org" > .env
docker compose -f lite.yml up -d
```

That is the whole install. `lite.yml` is self-contained, and the one thing it cannot know is who the super-admin is: `BIGBOOK_ADMIN_EMAIL` is required and has no default. Without it the `bigbook` container restarts in a loop, and `docker compose -f lite.yml logs bigbook` says `BIGBOOK_ADMIN_EMAIL is not set`.

> **Before v0.1 ships** the Big Book server image is not published, so the second command cannot pull it. Until then, build it from a checkout: [CONTRIBUTING.md §3](../../CONTRIBUTING.md#3-set-up).

## Check it

```
docker compose -f lite.yml ps
```

All three show `healthy`. "Healthy" means ready to serve, not merely started; for `bigbook` it also means first-boot bootstrap has finished. Then:

```
curl http://localhost:8080/fhir/R4/metadata
```

returns a FHIR `CapabilityStatement`.

| What | Where |
|---|---|
| FHIR API | `http://localhost:8080/fhir/R4` — needs a bearer token from realm `bigbook`, requested with scope `organization:<project id>`; only `/metadata` and the health endpoints are open |
| Server health | `http://localhost:8080/actuator/health` |
| Keycloak admin console | `http://localhost:8081`, user `admin` |
| Postgres | not published to the host; `docker compose -f lite.yml exec postgres psql -U bigbook` |

## Your super-admin

On first boot Big Book creates, from `BIGBOOK_ADMIN_EMAIL`: a Keycloak user in realm `bigbook`, a super-admin project (a Keycloak organisation and a HAPI partition), and that user's admin seat in it. Starting again changes nothing.

You did not set a password, so one was generated and written to the log exactly once:

```
docker compose -f lite.yml logs bigbook | grep "Generated super-admin password"
```

Copy it now: it is stored nowhere else, and recreating the container discards that log. To choose it yourself, put `BIGBOOK_ADMIN_PASSWORD` in `.env` before the first start. There is no default password, and Medplum's `medplum_admin` is refused.

## Secrets

Nothing secret is in `lite.yml`. On first boot the stack generates a Postgres password, a Keycloak admin password and the secret of Big Book's own Keycloak client, 32 random characters each, into a Docker volume that only the three containers mount. To read the Keycloak one:

```
docker compose -f lite.yml exec postgres cat /run/bigbook/keycloak-admin-password
```

Keycloak treats this first admin as temporary and says so in its console; create your own admin user and delete it.

To choose the passwords yourself, put a `.env` beside `lite.yml` **before the first start** ([`.env.example`](../../deploy/compose/.env.example) has the five keys). They are first-boot inputs: Postgres and Keycloak read them once, so changing `.env` later changes nothing. Every key is in [config.md](config.md).

## Stop, start, remove

```
docker compose -f lite.yml down        # stop; data and secrets are kept
docker compose -f lite.yml up -d       # start again, same data, same secrets
docker compose -f lite.yml down -v     # remove everything, including all data
```

## Anywhere other than localhost

Set `BIGBOOK_BASE_URL` in `.env` to the public origin, with the trailing slash, for example `https://bigbook.example.org/`. It is the base of every link the FHIR API emits. Keycloak refuses plain-HTTP logins from public addresses; `lite` does not terminate TLS, so put a TLS proxy in front, or use the `full` profile when it lands.

## The admin console is separate

```
docker compose -f lite.yml -f admin.yml up -d
```

adds a fourth container, Appsmith CE, with the Big Book admin app (ADR-004). It is an overlay, **not part of `lite` and not on the ten-minute clock**: budget up to three more minutes for it on a cold cache. `admin.yml` and its guide, `docs/guides/admin-ui.md`, arrive with issue #16.

## If it does not come up

- `docker compose -f lite.yml logs bigbook` (or `keycloak`, `postgres`).
- A validation error about `configs` or `content` before anything starts: Compose is older than v2.23.1.
- Port already in use: something else holds 8080 or 8081.
- `bigbook` keeps restarting and its log says `BIGBOOK_ADMIN_EMAIL is not set`: add it to `.env`.
- `Bootstrap attempt failed, retrying` a few times at first start is normal: Big Book starts faster than Keycloak and waits for it. It gives up, and the container restarts, after five minutes.
- `Could not resolve placeholder 'postgres-password'`: the server started without the secrets volume. Start it through `lite.yml`, or set `SPRING_DATASOURCE_PASSWORD`.
