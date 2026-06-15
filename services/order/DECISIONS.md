# Order Service — Implementation Decisions

This file records judgment calls made while scaffolding the Order Service (Phase 1)
that are NOT explicitly dictated by an existing spec. Review and back-port into the
official docs (SRS / System Design / API_SPEC) as appropriate.

## Phase 1 — Scaffold

### Database name
- Default datasource URL uses database `order_db` (`jdbc:postgresql://localhost:5432/order_db`),
  mirroring the wallet service's `wallet_db` convention. Override via `SPRING_DATASOURCE_URL`.

### Outbox payload column & JSON mapping
- The migration uses `payload_json JSONB` (per the prescribed schema). The JPA mapping on
  `OrderOutbox.payloadJson` stores the field as a `String` annotated with
  `@JdbcTypeCode(SqlTypes.JSON)` + `columnDefinition = "jsonb"`. The wallet outbox used a
  plain `TEXT` column; here we follow the JSONB schema requested for order_outbox while
  keeping the in-memory representation a serialized JSON `String` (serialized via ObjectMapper).
- `OrderOutbox.of(...)` defaults `aggregateType = "Order"` and sets `partitionKey = aggregateId`
  (the order id) so events for the same order are routed to the same Kafka partition.

### Exception base classes
- Order domain exceptions extend the shared `com.haizz.exchange.common.web.*` exception
  hierarchy (`NotFoundException`, `ConflictException`, `ValidationException`,
  `ServiceUnavailableException`) rather than defining a local `OrderException` base like
  wallet did. This reuses the shared `exchange-common` module (which this service now depends on)
  and its `getHttpStatus()` contract, so the `GlobalExceptionHandler` maps any `BaseException`
  generically by its declared status.

### GlobalExceptionHandler
- Added a minimal handler (optional for this phase) that maps `BaseException` -> declared HTTP
  status, handles bean-validation errors, and falls back to 500. It uses a local
  `api/ErrorResponse` record mirroring wallet's response shape (status/error/errorCode/message/
  path/timestamp/details) for consistency across services.

### Order entity enums
- `side` and `type` use the shared `com.haizz.exchange.common.enums.OrderSide` / `OrderType`
  enums, stored as STRING. `state` uses the service-local `OrderState` enum (NEW, OPEN,
  PARTIALLY_FILLED, FILLED, CANCEL_REQUESTED, CANCELLED, REJECTED) since the lifecycle states
  are specific to the order service and richer than the shared `OrderStatus`.

### HTTP client library
- Used Spring `RestClient` (synchronous, available in Spring Boot 4 / spring-web) for both
  `WalletClient` and `MarketDataClient`, built in a `@PostConstruct` from the base URLs in
  `AppProperties.clients()`. Chosen over `WebClient` to avoid pulling in WebFlux for simple
  blocking calls.

### User identity
- Not yet wired in this phase (no controllers). When place/cancel are implemented, user id is
  expected to come from the JWT subject (resource-server `Authentication`), consistent with the
  other services; the wallet internal endpoints take an explicit `userId` in the body.

### Reference tables
- `assets`, `trading_pairs`, and `fee_schedules` are seeded directly in the V1 migration with the
  values given in the task. Only `TradingPair` has a JPA entity + repository for now; `assets`
  and `fee_schedules` are reference-only and will get entities when needed by later phases.
