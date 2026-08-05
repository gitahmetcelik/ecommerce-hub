# ecommerce-hub

A multi-channel e-commerce order management platform — one place to ingest orders
from marketplace channels (Trendyol, Shopify, ...), keep stock and order state
consistent, and push updates back out, without losing anything when a channel's
API misbehaves.

Built as a set of Spring Boot modules on top of `gorev-motoru`, a companion task
engine handling retries, scheduling, and dead-letter handling, with Postgres
row-level security enforcing tenant isolation at the database layer.

## Status

Early build-out, phase by phase. Currently on the mock connector only — no live
marketplace integration yet.

| Phase | Scope | Status |
|---|---|---|
| 0a | Skeleton, canonical schema, RLS isolation | ✅ |
| 0b | Work dispatcher, org round-robin, orphan sweeper | ✅ |
| 0c | Call intents, TaskKey discipline, credential encryption, retention | ✅ |
| 1 | Connector SDK, rate-limit budgets, mock marketplace | ✅ |
| 2 | Ingest pipeline, order state machine, stock ledger | ✅ |
| 3 | Catalog matching, operator queue, resumable backfill | ✅ |
| 4 | Push coalescing, buffering, oversell handling, reconcile | 🔜 |

## Modules

- **hub-domain** — core entities and business logic (orders, stock, catalog matching, tenancy)
- **connector-sdk** — the interface every marketplace connector implements, plus the rate-limit budget
- **connector-mock** — a connector talking to the bundled mock marketplace (`mock-pazaryeri/`)
- **ingest** — webhook intake, signature verification, outbox
- **dispatcher** — turns outbox rows into real engine tasks, org-fair scheduling
- **task-handlers** — engine task handlers (order processing, etc.)
- **app** — the Spring Boot application tying everything together, plus the internal ops screen

## Running locally

```bash
docker compose up -d          # Postgres + RabbitMQ (delayed-exchange image, required by the task engine)
mvn -o install -DskipTests    # build all modules
mvn -o spring-boot:run -pl backend/app
```

Tests spin up their own Postgres and mock-marketplace containers via Testcontainers:

```bash
mvn -o test
```

## Design notes

- **Tenant isolation** is enforced by Postgres RLS, not just application code — every tenant-scoped
  query runs under a role that can only see its own `organization_id`.
- **Every webhook carries the full current order state**, not a delta — this is what lets
  out-of-order delivery (e.g. a payment event arriving before the order-created event) resolve
  correctly without a separate reordering buffer.
- **Catalog matching never silently invents data.** Order processing matches incoming items by SKU
  then barcode; anything ambiguous or unmatched goes to an operator queue instead of being guessed.
  Backfill is the one path allowed to auto-create catalog entries, since it's importing directly
  from the channel's own source of truth.
- **Backfill is resumable and low-priority.** Progress is a cursor persisted after every page, and
  it always runs under the lowest-priority rate-limit budget class so it never starves live traffic.
