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

## Phase 2 — Consumers, index, startup rebuild, feed status (SR-050)

### Per-pair single-threaded execution model
- `PairExecutorRegistry` (`infrastructure/index`) lazily creates ONE single-thread
  `ExecutorService` per pair (`ConcurrentHashMap<String, ExecutorService>`), named
  `pair-<symbol>-N`, daemon threads. `submit(pair, task)` serializes all work for a pair into
  a deterministic FIFO sequence. This is the ONLY safe entry point for index mutation + matching
  per pair — it's what lets `OpenOrdersIndex` stay non-concurrent. Task exceptions are caught so
  a single bad task never kills the pair's worker thread. `@PreDestroy` shuts executors down
  (5s graceful, then `shutdownNow`).
- Both consumers wrap their dispatch in `pairExecutorRegistry.submit(pair, ...)` rather than
  calling dispatchers directly. Feed-status updates (degraded/recovered) bypass the executor
  since `FeedStatusRegistry` is already thread-safe and order-insensitive.

### Event routing: two-pass JSON deserialize
- Consumers first deserialize `EventEnvelope<Object>` to peek `eventType`, then re-deserialize
  with the concrete payload `TypeReference`. Chose clarity over a single custom deserializer;
  cost is one extra parse per record, acceptable for this throughput. Unknown eventTypes
  (e.g. `PairMetadataUpdatedEvent`, `DepthUpdatedEvent`) are logged at debug and ignored.
  All processing is wrapped in try/catch so a poison message never crashes the listener.
- Market-data eventType matching uses the full class-style names confirmed from the producer
  (`ExternalTradeObservedEvent`, `MarketDataFeedDegradedEvent`, `MarketDataFeedRecoveredEvent`),
  whereas order events use short names (`OrderPlaced`, `OrderCancelled`) — mirrors each
  producer's actual `eventType` string.

### Consumer groupId
- Both listeners use groupId `matching-engine` and rely on the shared
  `kafkaListenerContainerFactory` (manual ack, RECORD ack mode) from `KafkaConfig`.

### Aggressing-side inference for external trades
- `MatchDispatcher.onExternalTrade` infers which resting side is eligible from `buyerIsMaker`:
  if the external BUYER is the maker, the external taker was a SELLER hitting bids → OUR resting
  BUY limit orders are candidates; otherwise OUR resting SELL limit orders are candidates.
- Trades for a pair whose feed is DEGRADED/DISCONNECTED (`FeedStatusRegistry.isTradeable` false)
  are skipped with a warning — we don't trust the external price while the feed is unhealthy.

### Feed status registry
- `FeedStatusRegistry` (`domain`) is a thread-safe `ConcurrentHashMap<String, FeedState>` with
  enum `{HEALTHY, STALE, DEGRADED, DISCONNECTED}` + `lastUpdate`. Unknown pairs default HEALTHY
  (and therefore tradeable) — fail-open so a pair with no feed event yet isn't blocked. STALE is
  defined for a later staleness-detector phase but is currently unused/tradeable.

### Eligibility rule (`OpenOrdersIndex.eligibleLimitOrders`)
- BUY (bid) limit orders eligible when `limitPrice >= externalPrice`; SELL (ask) limit orders
  eligible when `limitPrice <= externalPrice`. Implemented by iterating the relevant side's
  per-price `TreeMap`, collecting orders that meet the price condition, then sorting the whole
  eligible set by `createdAt` ascending (FIFO fairness across the set, per spec). Correctness
  over micro-optimization for now. MARKET ResidentOrders never rest, so they're never returned.

### Hook seams for phase 3
- `MarketOrderHook` + `LimitMatchHook` interfaces with no-op logging `@Component` impls
  (`NoOpMarketOrderHook`, `NoOpLimitMatchHook`). Phase 3 implements the real fill / trade-emission
  logic by replacing these beans — no rewiring of consumers/dispatchers needed. Dispatcher bodies
  carry `// TODO(phase3)` markers where fills go.
- `OrderDispatcher.onOrderPlaced`: MARKET → `marketOrderHook.handle(ro)` (NOT indexed —
  executes immediately in phase 3); LIMIT → `openOrdersIndex.add(ro)`.

### Startup rebuild + resilience
- `IndexRebuildService` listens for `ApplicationReadyEvent` and pages
  `OrderClient.fetchOpenOrders(page, 200)` from page 0 until an empty/short page or last page,
  building a `ResidentOrder` per LIMIT order and adding to the index. Non-LIMIT (MARKET) rows are
  skipped defensively (shouldn't be resting).
- Idempotent: each order is `remove`d before `add`, so a re-run rebuilds cleanly without dupes.
- Resilient: the whole loop is wrapped in try/catch — if the Order service is unreachable the app
  logs a WARN and boots in a DEGRADED state (live Kafka events still processed) rather than
  crashing. A retry/reconnect is left as a TODO.
- Rebuild mutates the index directly (single rebuild thread, before live event traffic matters);
  once consumers are driving events all further mutation goes through the per-pair executor.
