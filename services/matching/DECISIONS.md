# Matching Engine — Implementation Decisions

Per-service log of judgment calls not dictated by the specs (SRS / System Design /
API_SPEC / CLAUDE.md). Review and back-port into the official docs as needed.

## Phase 1 — Scaffold

### DB name `match_db`
- Default datasource URL uses database `match_db` (`jdbc:postgresql://localhost:5432/match_db`),
  following the per-service DB convention (wallet → `wallet_db`, marketdata → `marketdata_db`).
  Chose `match_db` over `matching_db` for brevity; override via `SPRING_DATASOURCE_URL`.

### Outbox: topic stored per row
- `matching_outbox` has its own `topic` column (and `partition_key`) and the relay reads the
  topic from the row instead of resolving it from `event_type` (as wallet's relay does).
  Reason: matching publishes to TWO topics — `trade.executed` and `matching.events.v1` — so a
  single eventType→topic switch is insufficient. This keeps the relay topic-agnostic.
- `aggregate_type` defaults to `'Trade'` (column default + entity default). `partition_key` is
  currently set equal to `aggregate_id` by the publisher; a later phase may key by pair instead.

### Outbox payload column type: JSONB
- `payload_json` is `JSONB` (spec'd) rather than wallet's `TEXT`. Mapped on the entity as a
  `String` with `@JdbcTypeCode(SqlTypes.JSON)` + `columnDefinition = "jsonb"` so Hibernate
  `ddl-auto: validate` accepts it and we still hand Kafka a raw JSON string.

### EventEnvelope wrapping
- Both topics carry `com.haizz.exchange.common.event.EventEnvelope`. `MatchingOutboxPublisher`
  wraps the payload with `EventEnvelope.of(randomId, eventType, "matching-engine", correlationId, payload)`.
  `correlationId` is pulled from MDC (set by `CorrelationIdFilter`); may be null for
  non-HTTP-originated events (e.g. Kafka-consumer-driven) — acceptable for now.

### In-memory index design
- `OpenOrdersIndex` is a `@Component` keyed by pair → `PerPairIndex`. `PerPairIndex` uses
  `TreeMap<BigDecimal, Deque<ResidentOrder>>` for bids (reverse order) / asks (natural order),
  plus a `Map<UUID, ResidentOrder> byId`. A global `Map<UUID, String> pairByOrderId` lets
  `remove(orderId)` / `get(orderId)` work without the pair.
- Plain (non-concurrent) collections: a later phase routes per-pair mutations through a
  single-threaded executor, so no synchronization is needed yet.
- `eligibleLimitOrders(...)` is a compiling STUB returning an empty list; price-time candidate
  selection lands in the matching-core phase. MARKET ResidentOrders are tracked by id but do
  not rest in the bid/ask books.

### HTTP clients: Spring `RestClient`
- `MarketDataClient` / `OrderClient` use Spring 6 `RestClient` (built per-bean from the base
  URLs in `matching.clients.*`). DTOs are minimal Java records; JSON snake_case fields
  (`updated_at`, `overall_status`, `total_elements`, `total_pages`) mapped via `@JsonProperty`.
  Order's internal projection is camelCase, so those fields bind by name.

### Module dependency scope
- This branch was cut from `main`, which has no Order module. `services/matching` depends only on
  `exchange-common` at compile time; Order/Market Data integration is runtime-only (REST + Kafka).

### docker-compose left untouched
- `docker-compose.yml` defines infra only (app services are commented out); per repo convention
  app services run via `start-all.ps1`. No `matching` service added to compose.
