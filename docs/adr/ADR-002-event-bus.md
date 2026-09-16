# ADR-002 Event bus

Status: decided (2026-09-17)
Decision: **None — in both profiles, for v0.1 and v0.2.** No Kafka, no RabbitMQ, no Redis. Asynchronous work runs in the Big Book JVM: HAPI's subscription matcher feeds a Postgres-backed delivery table polled with `FOR UPDATE SKIP LOCKED`; cron is Spring scheduling with a DB-backed lock; bots (v0.2) are Camel routes invoked in-process from the same table. The `full` overlay drops its "event bus" slot.
Context: see `docs/BIGBOOK.md` → Open decisions; `docs/inventory/area-6-workers-subscriptions.md` (D53, D55, D60, D61). Requirements previously listed as blocked: BB-R-007 (v0.2 extras), BB-R-016 — neither is.

## Rationale
- Medplum's "event bus" is BullMQ on Redis: a durable job queue with per-job retry, not a message broker. Its only real pub/sub fans a WebSocket event to whichever node holds the socket — a multi-node problem `lite` (one JVM, ADR-006) does not have.
- HAPI already supplies the matching half (`SubscriptionMatcherInterceptor`). Its delivery queue is an in-memory `LinkedBlockingQueue`, so a restart drops pending deliveries; durability must be Big Book glue regardless of which transport sits behind it. A Postgres table is the smallest durable answer and is the same table a second JVM would share.
- A broker adds a container to `full`, a client library, a schema for messages, and an upgrade line — for zero v0.x capability.
- Camel routes run on the poller's threads; BB-R-016 needs no transport.

## Consequences
- `subscription_delivery(id, subscription_id, resource_type, resource_id, version_id, interaction, attempt, next_attempt_at, status, consecutive_failures, first_failure_at)` in the `bigbook` schema, polled by one `@Scheduled` at 1 s. ≈150 glue lines, charged to BB-R-007. Keeps the 5 s exit test; survives restart; carries the auto-disable counter (BB-R-007 v0.2) without Redis.
- Retry defaults pinned in BB-R-007.3: 4 attempts, 20 s base, ×2, 8 h cap, ±10% jitter, `subscription-max-attempts` honoured. Preamble/delivery split not adopted.
- Cron (BB-R-016 v0.2): `Cron` resource is the schedulable; job key = `Cron/<id>`; DB-backed lock, no external scheduler.
- WebSocket subscriptions (BB-R-025 v0.2): in-JVM `Map<subscriptionId, sessions>`; **single-node only** until this ADR is revisited. A multi-replica `full` needs sticky sessions at Traefik or a broker.
- Docs in this commit: BIGBOOK.md stack row "Event bus" → "none (Postgres delivery table); revisit per ADR-002 triggers"; BIGBOOK.md Open decisions → ADR-002 decided; ARCHITECTURE.md §1 removes the `EB` node, §2(c) inserts the delivery table between `SUBSCRIPTION_RESOURCE_MATCHED` and delivery; `full` container count drops by one.

## Reversal cost
Low. The delivery table is the seam: a broker replaces the poller, not the matcher, the hooks, the signature or the AuditEvent write. Camel routes are transport-agnostic by design.
Triggers for reversal, either one: (a) a `full` deployment runs more than one Big Book replica **and** needs WebSocket subscriptions; (b) BB-R-016 bots move to dedicated worker nodes. Neither is on the v0.1–v0.3 roadmap.

## Open — verify first, tracked on issue #12
- Confirm the HAPI hook order on the pinned version lets the table insert replace `SubscriptionDeliveryQueue` rather than run beside it. A "no" is a double-delivery bug: stop and raise, do not work around.
- Confirm HAPI's rest-hook delivery can be driven from the poller (`ResourceDeliveryMessage` reuse) so the HTTP client, headers and interaction filter stay HAPI's. A "no" changes the ≈150-line estimate; report the new number.