# OrderFlow

> Event-driven order processing platform built with Java 21, Spring Boot 3 and Apache Kafka.

Distributed order processing across four services, coordinated by a Saga orchestrator with compensating transactions. Built to explore the hard parts of distributed systems — exactly-once-ish event delivery, idempotency, and concurrency control — rather than to maximise feature count.

> ⚠️ **Work in progress.**

---

## Architecture

```
Frontend (React + Vite)
     │
     ▼
API Gateway ──── JWT auth, Redis rate limiting
     │
┌────┼──────────┐
▼    ▼          ▼
Order Inventory Payment      ← one PostgreSQL database each
(Saga (Redis    (Idempotency
 orch) lock)     key)
└────┼──────────┘
     ▼
   KAFKA
     ▼
Notification
```

Each service owns its database. No cross-service joins, no shared schema — services communicate only through Kafka events or REST.

## Key patterns

| Pattern | Problem it solves |
|---|---|
| **Saga (orchestration)** | No distributed transaction across three databases |
| **Transactional Outbox** | DB commit succeeds but Kafka publish fails → lost event |
| **Idempotent consumer** | Kafka is at-least-once → duplicate delivery |
| **Redis distributed lock** | Overselling under concurrent checkout |
| **Dead letter topic + retry** | A poison message blocking a partition |
| **Cache-aside + event invalidation** | Stale catalog data |
| **Distributed tracing** | Following one request across four services |
| **Testcontainers** | Integration tests against real Kafka/Postgres/Redis |

Each is documented inline at the point of use — see the `Design notes` comment block at the top of the relevant class.

## Tech stack

**Backend** — Java 21, Spring Boot 3.3, Spring Cloud Gateway, Spring Security (JWT), Apache Kafka, Redis (Redisson), PostgreSQL, Flyway, Hibernate/JPA, OpenTelemetry + Jaeger, Testcontainers, SpringDoc OpenAPI

**Frontend** — React 18, TypeScript, Vite, TanStack Query, Zustand, React Router, Tailwind CSS

**Infra** — Docker Compose, GitHub Actions

## Project layout

```
orderflow/
├── backend/
│   ├── services/
│   │   ├── api-gateway/
│   │   ├── order-service/          # Saga orchestrator
│   │   ├── inventory-service/      # Redis distributed lock
│   │   ├── payment-service/        # Idempotency keys
│   │   └── notification-service/
│   ├── shared/event-contracts/     # Shared event envelope
│   └── pom.xml
├── frontend/
│   └── src/{pages,components,api,hooks}/
└── infra/docker/
```

## Getting started

```bash
# Infrastructure (Kafka, Redis, PostgreSQL ×3, Jaeger)
docker compose -f infra/docker/docker-compose.yml up -d

# Backend
cd backend && ./mvnw spring-boot:run -pl services/order-service

# Frontend
cd frontend && npm install && npm run dev
```

| Service | URL |
|---|---|
| Frontend | http://localhost:5173 |
| API Gateway | http://localhost:8080 |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| Jaeger | http://localhost:16686 |

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
