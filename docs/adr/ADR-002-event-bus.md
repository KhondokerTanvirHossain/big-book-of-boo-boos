# ADR-002 Event bus

Status: decided (2026-09-17)
Decision: **None.** No broker in either profile for v0.1–v0.2. Subscription delivery, cron and retries run on a Postgres-backed delivery table plus a Spring scheduler inside the Big Book JVM. Kafka and RabbitMQ are not adopted.
Context: see `docs/BIGBOOK.md` → Open decisions. Previously listed as blocking BB-R-007 (v0.2 extras) and BB-R-016; the inventory (area 6, D53) showed neither is blocked. Rationale text below is drafted from the inventory; the Architect's text replaces it on "go".

## Options

- **A** — Kafka. Durable log, partitions, consumer groups. Adds a broker (and ZooKeeper/KRaft) to the stack.
- **B** — RabbitMQ. Broker with queues, exchanges, DLX. Adds one container.
- **C** — None. Postgres table as the durable queue, `@Scheduled` poller with `FOR UPDATE SKIP LOCKED`, Quartz/ShedLock for cron. **Chosen.**

## Rationale (draft from inventory D53, D55, D61 — Architect text supersedes)

- Medplum's "event bus" is BullMQ on Redis: a durable **job queue** with per-job retry and backoff, not a broker. Nothing in the subscription, cron, download or bot paths is pub/sub between services. The only true pub/sub in Medplum (`pubsub.ts`) exists to fan a WebSocket event out to whichever HTTP node holds the socket — a multi-node concern a one-JVM `lite` does not have.
- HAPI's own subscription delivery channel is an in-memory `LinkedBlockingQueue` by default; pending deliveries are lost on restart. A Postgres table written in the same transaction as the triggering resource, polled every second with `SKIP LOCKED`, gives durability for ~150 lines and is already the shape a second JVM would share.
- `lite` is three containers (ADR-006). A broker is a fourth on every subscription's path and a second thing to operate on a laptop.
- BB-R-007's 5-second exit test is met by the poller (worst case ≈ 1 s poll + RTT). At-least-once, unordered delivery is what Medplum offers too; a broker would not change that contract.
- Cron: Quartz with the Postgres JobStore (or `@Scheduled` + ShedLock) keyed on the `Cron` resource id covers BB-R-016 without a broker.

## Decision

| Profile | v0.1–v0.2 | Queue | Cron |
|---|---|---|---|
| `lite` | none | `subscription_delivery` table, 1 s poller, `FOR UPDATE SKIP LOCKED` | Quartz (Postgres JobStore) or ShedLock |
| `full` | none | same table; multiple replicas share it safely via `SKIP LOCKED` | same, one active scheduler |

## Consequences

- BB-R-007 fill becomes "wire HAPI matching + glue delivery" (REQUIREMENTS.md, 2026-09-17). ARCHITECTURE.md §2(c) shows the table and poller instead of HAPI's delivery queue.
- BB-R-025 WebSocket subscriptions are **`lite`-only single-node** (in-JVM session map). A multi-replica `full` deployment with WebSocket subscriptions needs sticky sessions at Traefik or reopens this ADR. That is v0.2+ and the only known trigger.
- `EB` node removed from the ARCHITECTURE.md component diagram; BIGBOOK.md stack row "Event bus" → none.
- Every `redis.*` config key from Medplum is dropped (area 4, D33).

## Revisit triggers

1. Big Book runs more than one JVM **and** needs WebSocket subscriptions across them (BB-R-025).
2. BB-R-016 wants bots on separate worker nodes.
3. A pilot's delivery volume makes a 1 s poll on Postgres the bottleneck (measure first; batch the poll before adding a broker).
