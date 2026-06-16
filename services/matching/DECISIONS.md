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

## Phase 3 — Core matching: fills, trades, event emission (SR-051–058)

### Replaced no-op hooks with real impls
- Deleted `NoOpMarketOrderHook` / `NoOpLimitMatchHook`; added `MarketOrderMatcher`
  (implements `MarketOrderHook`) and `LimitOrderMatcher` (implements `LimitMatchHook`),
  each a plain `@Component`. Spring now injects the single real bean per interface into the
  dispatchers — no rewiring of consumers/dispatchers needed (the seams from phase 2 held).

### Market order: walk-the-book + slippage + VWAP
- BUY walks `asks`, SELL walks `bids` (the depth response already orders each side best→worst,
  so no extra sort). At each level: `fillQty = min(remaining, levelQty)`;
  `fillPrice = levelPrice × (1 ± marketSlippage)` (+for BUY, − for SELL), rounded to **scale 8,
  HALF_UP**. Levels with qty ≤ 0 or malformed (< 2 fields) are skipped. Depth requested = 20.
- VWAP / `avgPrice` on `OrderFilled` = Σ(qty×price)/Σqty over the batch, **scale 18, HALF_UP**.

### Market partial → auto-cancel; rejection rules
- If depth is exhausted before the order fully fills, we emit the per-fill events plus a final
  `OrderCancelled` reason=`"MARKET_PARTIAL"` (market orders can't rest). The LAST fill in this
  case is still marked `isFinalFill=true` (it is the last fill of the order's life).
- Market order rejected (feed DEGRADED/DISCONNECTED, empty depth, depth fetch failure, or zero
  fillable levels) → single `OrderCancelled` reason=`"REJECTED"`, no trades.
- Note: SRS SR-MATCH-ME-001 names the empty-depth reason `DEPTH_EXHAUSTED` / `OrderRejected`;
  per the phase-3 task contract we use the existing `OrderCancelledEvent` with reason `REJECTED`
  for ALL market-reject cases (no separate `OrderRejected` event exists in exchange-common).

### Limit order: FIFO distribution + fill-price rule
- `LimitOrderMatcher` distributes the external trade volume across the eligible FIFO list:
  `fillQty = min(order.remaining, externalRemaining)`, stop when external volume is exhausted.
- **Fill price** (DEVIATION from SRS SR-MATCH-ME-002 step 4, which says
  `price = external_trade.price`): per the phase-3 task contract we use the price more
  favorable to the resting order — BUY → `min(limitPrice, externalPrice)`,
  SELL → `max(limitPrice, externalPrice)`. Since `eligibleLimitOrders` only returns orders whose
  limit already satisfies the touch condition, this differs from `externalPrice` only when the
  resting limit is strictly better, in which case the resting order keeps its better price.
  Flagged for back-port review.
- On fill: `order.addFill(qty)`; if fully filled → `openOrdersIndex.remove(orderId)` +
  `OrderFilled`, else `OrderPartiallyFilled`. `isFinalFill` on the trade event = order fully
  filled.

### Fee rules (all fills TAKER in MVP)
- BUY  → `feeAmount = quantity × takerRate`, `feeAsset = baseAsset`.
- SELL → `feeAmount = quantity × price × takerRate`, `feeAsset = quoteAsset`.
- `role` is always `"TAKER"` (SR-MATCH-ME-003: maker/taker not tracked per-order in MVP and
  rates are equal). Fee rounded to scale 8, HALF_UP.

### residualFrozenAmount = 0 / residualAsset = null — Order owns residual
- The matching engine does NOT compute the placement-time freeze, so EVERY `TradeExecuted`
  event sets `residualFrozenAmount = BigDecimal.ZERO` and `residualAsset = null`. Wallet skips
  residual release when the amount is 0; the Order service releases any residual freeze itself
  on terminal (a separate Order-side phase). Matching never tries to release freeze.
- `isFinalFill = true` only on the fill that completes the order (or the last fill of a market
  order before auto-cancel), else false — so Wallet knows when an order is done.

### `quoteQuantity` and Trade row
- `quoteQuantity = quantity × fillPrice` (the FILL price, not slippage-of-slippage), scale 8.
  Persisted to the `Trade.quote_amount` column and sent on the event as `quoteQuantity`.
- Limit fills carry `externalTradeId = null` for now (the index/match path doesn't thread the
  external trade id through yet); market fills are also null. Column is nullable.

### Atomicity: one transaction per order's fill batch (`FillEmitter`)
- `FillEmitter` is a `@Component` with `@Transactional` methods. For ONE order's batch it saves
  all `Trade` rows + enqueues all `TradeExecuted` events + enqueues the lifecycle event
  (`OrderFilled`/`OrderPartiallyFilled`/`OrderCancelled`) through `MatchingOutboxPublisher` in a
  single transaction → atomic with the outbox (all land or none).
- A market order = one batch = one transaction. A single external trade touching N limit orders
  = N independent per-order transactions (simpler; acceptable per task contract — each order is
  its own atomic unit).
- `MatchingOutboxPublisher.enqueue` is `Propagation.MANDATORY`, so it requires the caller's
  transaction; `FillEmitter`'s `@Transactional` boundary supplies it. The matchers (which run on
  the per-pair executor) call `FillEmitter` AFTER finishing the in-memory walk/distribution, so
  index mutation stays single-threaded and DB work happens inside the transaction.

### Pair metadata lookup + cache (`MarketDataClient.getPairMetadata`)
- Added `getPairMetadata(pair)` → GET `/internal/pairs/{pair}/metadata` (snake_case record:
  base_asset, quote_asset, tick_size, step_size, min_notional). Results cached in a
  `ConcurrentHashMap` (static reference data) to avoid a per-fill HTTP call.
- Graceful fallback: on call failure/empty, log a WARN and derive metadata from the symbol
  (quote = `USDT`, base = symbol minus the `USDT` suffix) so fee/trade-event resolution never
  blocks a fill. Used to resolve baseAsset/quoteAsset for fees + the trade event.

## Phase 4 — Unit tests, trades read endpoint, docs (SR-055–059)

### Test strategy: pure unit, no Spring / no Testcontainers
- All tests are plain JUnit 5 + Mockito + AssertJ (no `@SpringBootTest`, no Kafka/Postgres
  Testcontainers), mirroring the Order service's `domain/*Test` style. They run in ~1s.
  Rationale: the core matching logic is pure once collaborators are mocked, so a fast unit
  suite gives the highest signal per second; a real Kafka/Postgres integration suite is left as
  a follow-up (tracked in TODO §3.4). The module still declares the Testcontainers deps for that
  later phase.
- `BigDecimal` assertions use `isEqualByComparingTo` (compareTo), never `equals`, to avoid scale
  mismatches (e.g. `60000` vs `60000.00000000`).
- Collaborators stubbed/captured with Mockito: `MarketOrderMatcher` and `LimitOrderMatcher` are
  tested by mocking `FillEmitter` and capturing the emitted `List<Fill>` + the
  terminalFilled/marketPartial flags — i.e. asserting the fills/events that WOULD be persisted,
  not the DB rows. `FillEmitter` itself is tested with a mocked `TradeRepository` /
  `MatchingOutboxPublisher` / `MarketDataClient`, capturing the `Trade` saved and the
  `TradeExecuted` / lifecycle events enqueued.

### No production refactor was required for testability
- The phase-1–3 seams (matchers delegate the persisted batch to `FillEmitter`;
  `OpenOrdersIndex` / `FeedStatusRegistry` are plain components) were already pure enough to unit
  test directly. No helper-extraction or behavior-preserving refactor of `src/main` was needed —
  runtime behavior is unchanged.
- One Mockito note: `FillEmitterTest.setUp()` stubs `getPairMetadata` with
  `lenient()` because the `emitRejected` test path never resolves metadata (strict stubbing would
  otherwise flag it as unnecessary).

### Test coverage map (50 tests)
- `OpenOrdersIndexTest` (12): add/get/remove roundtrip, unknown order/pair, BUY/SELL eligibility
  (`>=` / `<=` externalPrice), FIFO ordering across price levels and within a level, empty/no-match
  edges, removed order not eligible, MARKET tracked-by-id-but-never-eligible.
- `MarketOrderMatcherTest` (10): BUY walks asks (3 fills 0.5/0.3/0.2 at `×1.0005`, last=final),
  fullyFilled vs MARKET_PARTIAL flags, VWAP range, depth-exhausted partial → auto-cancel,
  SELL walks bids `×0.9995`, slippage direction, degraded reject (no depth call), empty depth /
  depth-fetch-throws reject, zero-qty levels skipped.
- `LimitOrderMatcherTest` (9): degraded skip, BUY fill = `min(limit, external)`,
  SELL fill = `max(limit, external)` (both directions), fully-filled removed + final,
  partial stays + not final, external volume distributed FIFO across orders until exhausted,
  zero-remaining orders skipped.
- `FillEmitterTest` (9): BUY fee in baseAsset = `qty×takerRate`, SELL fee in quoteAsset =
  `qty×price×takerRate`, `quoteQuantity = qty×price`, role always TAKER, residual = 0 /
  residualAsset null, isFinalFill propagation, OrderFilled VWAP avgPrice, OrderPartiallyFilled
  (no cancel), MARKET_PARTIAL emits partial-then-cancel, emitRejected emits OrderCancelled with
  no Trade.
- `FeedStatusRegistryTest` (5) + `ResidentOrderTest` (3) + `TradesControllerTest` (2).

### Trades read endpoint — `GET /api/v1/trades`
- Added `TradesController` (`api`) + `TradeResponse` / `PageResponse<T>` DTOs (`api/dto`) +
  `TradeRepository.findByUserIdOrderByExecutedAtDesc(userId, pageable)`. JWT-authenticated; userId
  from `jwt.getSubject()`; newest-first; page size clamped to 200 (default 50). `SecurityConfig`
  already authenticates everything outside `/api/v1/matching/internal/**` + `/actuator/**`, so no
  security change was needed; the gateway already routes `/api/v1/trades/**` → :8084.
- DTOs are snake_case (`order_id`, `quote_quantity`, `fee_amount`, `executed_at`, `total_elements`,
  `total_pages`) via `@JsonProperty`, mirroring the public API contract used elsewhere. `PageResponse`
  is matching's own copy (Order's `PageResponse` is not on this branch's classpath) with the same
  field shape, so the gateway-facing JSON is consistent.
