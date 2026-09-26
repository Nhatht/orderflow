# OrderFlow

[![backend-ci](https://github.com/Nhatht/orderflow/actions/workflows/backend-ci.yml/badge.svg)](https://github.com/Nhatht/orderflow/actions/workflows/backend-ci.yml)

> Event-driven order processing platform built with Java 21, Spring Boot 3 and Apache Kafka.

Distributed order processing across four services, coordinated by a Saga orchestrator with compensating transactions. Built to explore the hard parts of distributed systems — reliable event delivery, idempotency, concurrency control, timeouts and tracing — rather than to maximise feature count.

> ⚠️ **Work in progress.** Backend complete (weeks 1–8); frontend not started yet.

---

## Architecture

```mermaid
flowchart TB
    client[Client] -->|JWT| gw[API Gateway<br/>auth · rate limit · identity · correlation id]
    gw --> order[Order Service<br/>saga orchestrator]
    gw -->|GET stock only| inv[Inventory Service<br/>Redis lock · cache]
    order <-->|events| kafka[(Kafka)]
    inv <-->|events| kafka
    pay[Payment Service<br/>idempotency key] <-->|events| kafka
    kafka --> notif[Notification Service<br/>email]
    order --- odb[(order_db)]
    inv --- idb[(inventory_db)]
    pay --- pdb[(payment_db)]
    notif --- ndb[(notification_db)]
    inv --- redis[(Redis)]
    gw --- redis
```

- **Each service owns its database.** No cross-service joins, no shared schema.
- **Services talk to each other only through Kafka.** The gateway is the single entry point from the outside, not a path between services.
- **The payment service is not reachable from outside** — it stores no owner, so it cannot check who may read a payment.

## Key patterns

| Pattern | Problem it solves | Where |
|---|---|---|
| **Saga (orchestration)** | No distributed transaction across three databases | `OrderSagaOrchestrator` |
| **Transactional Outbox** | DB commit succeeds but Kafka publish fails → lost event | `OutboxEventPublisher` + `OutboxPoller` in each service |
| **Idempotent consumer** | Kafka is at-least-once → duplicate delivery | `processed_events`, payment `UNIQUE(order_id)`, notification ledger |
| **Redis distributed lock** | Overselling under concurrent checkout | `RedissonLockAdapter`, `ReserveOrderStockService` |
| **Dead letter topic + retry** | A poison message blocking a partition | `KafkaConfig` in each service |
| **Cache-aside + event invalidation** | Stale stock shown to customers | `GetStockService`, `StockChangedListener` |
| **Distributed tracing** | Following one request across five services — through the outbox | `OutboxTracing`, Jaeger |
| **Testcontainers** | Integration tests against real Kafka / Postgres / Redis / SMTP | `*IT.java` |

Each is documented at the point of use — read the Javadoc at the top of the class.

## The saga

### Happy path

```mermaid
sequenceDiagram
    participant C as Client
    participant O as Order
    participant I as Inventory
    participant P as Payment
    participant N as Notification
    C->>O: POST /api/orders
    O-->>C: 201 PENDING
    O->>I: order.created
    I->>O: stock.reserved (held 30 min)
    O->>P: payment.requested
    P->>O: payment.completed
    O->>I: order.confirmed (stock leaves the warehouse)
    O->>N: order.confirmed (email)
```

### Card declined → compensation

```mermaid
sequenceDiagram
    participant O as Order
    participant I as Inventory
    participant P as Payment
    O->>I: order.created
    I->>O: stock.reserved
    O->>P: payment.requested
    P->>O: payment.failed (CARD_DECLINED)
    Note over O: saga COMPENSATING
    O->>I: order.cancelled
    I->>O: stock.released (stock back on the shelf)
    Note over O: saga COMPENSATED
```

### No payment within 3 minutes → saga timeout

```mermaid
sequenceDiagram
    participant O as Order
    participant I as Inventory
    participant P as Payment
    O->>P: payment.requested
    Note over P: payment service is down / lagging
    Note over O: 3 min without payment → SagaTimeoutJob
    O->>I: order.cancelled (PAYMENT_TIMEOUT)
    O->>P: order.cancelled (tombstone)
    I->>O: stock.released → COMPENSATED
    Note over P: comes back, finds the tombstone,<br/>never calls the payment gateway
```

The saga owns the only clock that matters (3 min). Stock reservations expire after 30 min — a safety net for orphaned orders, deliberately much longer so the two clocks never race.

## Getting started

Requires JDK 21, Maven 3.9+, Docker.

```bash
# 1. Infrastructure: Kafka, Redis, PostgreSQL ×4, Jaeger, MailHog, Kafka UI
docker compose -f infra/docker/docker-compose.yml up -d

# 2. Build (runs every unit + integration test; needs Docker for Testcontainers)
cd backend && mvn clean verify

# 3. Run each service in its own terminal (or from the IDE)
java -jar services/order-service/target/order-service-0.0.1-SNAPSHOT.jar
java -jar services/inventory-service/target/inventory-service-0.0.1-SNAPSHOT.jar
java -jar services/payment-service/target/payment-service-0.0.1-SNAPSHOT.jar
java -jar services/notification-service/target/notification-service-0.0.1-SNAPSHOT.jar
java -jar services/api-gateway/target/api-gateway-0.0.1-SNAPSHOT.jar
```

### Try it

```bash
# Log in (demo users: alice/alice123, bob/bob123)
TOKEN=$(curl -s -X POST localhost:8080/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"alice123"}' | sed -E 's/.*"accessToken":"([^"]+)".*/\1/')

# Place an order (a total ending in 99 is declined by the simulated gateway → compensation)
curl -X POST localhost:8080/api/orders -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"currency":"VND","items":[{"productId":"11111111-1111-1111-1111-111111111111","productName":"Coconut candy","quantity":2,"unitPrice":45000}]}'

# Watch the saga step by step
curl localhost:8080/api/orders/<orderId>/saga -H "Authorization: Bearer $TOKEN"
```

| Service | URL |
|---|---|
| API Gateway | http://localhost:8080 |
| Jaeger (traces) | http://localhost:16686 |
| MailHog (emails) | http://localhost:8025 |
| Kafka UI | http://localhost:8090 |
| Swagger UI (direct, not via gateway) | http://localhost:8081/swagger-ui.html · :8082 · :8083 |

Every log line carries `[service,traceId,spanId,correlationId]` — paste the `traceId` into Jaeger to see the whole request: one order produces a single trace of ~57 spans across all five services, including the outbox relays.

## Tests

```bash
cd backend
mvn test      # unit tests only — fast, no Docker
mvn verify    # + integration tests on real Kafka, PostgreSQL, Redis, MailHog (Testcontainers)
```

Integration tests prove the failure modes, not just the happy path — for example: Kafka paused mid-flight loses no event; 50 threads racing for the last item sell it once; a payment request redelivered after the gateway already charged does not charge twice; a cancellation that arrives *before* the payment request still prevents the charge. Several were validated by deliberately re-introducing the bug and watching the test turn red.

## Conventions

- Money is always `BigDecimal` / `NUMERIC(19,4)` — never `double`
- Every Kafka event carries `eventId`, `eventType`, `aggregateId`, `occurredAt`, `correlationId`, `payload`
- Every consumer is idempotent
- Schema changes go through Flyway, never `ddl-auto: update`
- Commits follow Conventional Commits

## Design decisions

**Saga orchestration over choreography** — the flow has several steps and needs compensation, so centralised state makes failures observable and debuggable.

**No two-phase commit** — 2PC holds locks across service boundaries for the whole transaction, its coordinator is a single point of failure, and neither Kafka nor payment gateways support XA.

**Four services, not more** — service boundaries follow business capability. Splitting further would add distribution cost without adding a reason to distribute.

**Transactional Outbox over direct publishing** — writing to the database and publishing to Kafka are two systems; a `@Transactional` block spanning both guarantees nothing. The outbox makes the write atomic and moves delivery to a separate, retryable step.

**Redis lock is not what prevents overselling** — optimistic locking (`@Version`) and a `CHECK (available_qty >= 0)` constraint guarantee correctness. Removing the Redis lock in a 50-thread test still sold exactly one item, but 18% of requests got a technical conflict instead of a clean "out of stock". The lock turns contention into order; the database guarantees correctness.

**One clock decides** — the saga times out unpaid orders after 3 minutes; stock reservations expire after 30. When both expired after 3 minutes they raced across two databases and could release the stock of an order that had just been paid. If the race still happens (order service down for longer than 30 minutes), confirming an expired reservation takes the stock back or logs `OVERSOLD`.

**Cache invalidation through the outbox** — every stock write emits `stock.changed` in the same transaction; a listener evicts the cache entry. "Commit, then delete from Redis" is a dual write that silently leaves stale data if the process dies in between.

**Tracing through the outbox** — the outbox breaks trace propagation (the relay runs later on a scheduler thread). Each outbox row stores the W3C `traceparent` of the request that wrote it, and the relay continues that trace.
