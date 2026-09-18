# Install Big Book (`lite`)

`lite` is three containers: Postgres, Keycloak and the Big Book server, which has HAPI FHIR inside it (ADR-006). Under ten minutes on a cold image cache; CI measures that on every change (BB-R-011.1).

## You need

- Docker with Compose **v2.23.1 or newer** (`docker compose version`). `lite.yml` carries its init scripts inline, which older Compose cannot read.
- About 3 GB of free memory, and ports **8080** and **8081** free.

## Install

```
curl -O https://raw.githubusercontent.com/KhondokerTanvirHossain/big-book-of-boo-boos/main/deploy/compose/lite.yml
docker compose -f lite.yml up -d
```

That is the whole install. No config file, no `.env`. `lite.yml` is self-contained.

> **Before v0.1 ships** the Big Book server image is not published, so the second command cannot pull it. Until then, build it from a checkout: [CONTRIBUTING.md §3](../../CONTRIBUTING.md#3-set-up).

## Check it

```
docker compose -f lite.yml ps
```

All three show `healthy`. "Healthy" means ready to serve, not merely started. Then:

```
curl http://localhost:8080/fhir/R4/metadata
```

returns a FHIR `CapabilityStatement`.

| What | Where |
|---|---|
| FHIR API | `http://localhost:8080/fhir/R4` |
| Server health | `http://localhost:8080/actuator/health` |
| Keycloak admin console | `http://localhost:8081`, user `admin` |
| Postgres | not published to the host; `docker compose -f lite.yml exec postgres psql -U bigbook` |

## Secrets

Nothing secret is in `lite.yml`. On first boot the stack generates a Postgres password and a Keycloak admin password, 32 random characters each, into a Docker volume that only the three containers mount. To read the Keycloak one:

```
docker compose -f lite.yml exec postgres cat /run/bigbook/keycloak-admin-password
```

Keycloak treats this first admin as temporary and says so in its console; create your own admin user and delete it.

To choose the passwords yourself, put a `.env` beside `lite.yml` **before the first start** ([`.env.example`](../../deploy/compose/.env.example) has the three keys). They are first-boot inputs: Postgres and Keycloak read them once, so changing `.env` later changes nothing. Every key is in [config.md](config.md).

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
- `Could not resolve placeholder 'postgres-password'`: the server started without the secrets volume. Start it through `lite.yml`, or set `SPRING_DATASOURCE_PASSWORD`.
