# Order Service — Implementation Decisions

> Back-ported into docs (API_SPEC §3, SRS/SystemDesign Order appendices) on 2026-06-17.

This file records judgment calls made while scaffolding the Order Service (Phase 1)
that are NOT explicitly dictated by an existing spec. Review and back-port into the
official docs (SRS / System Design / API_SPEC) as appropriate.

## 2026-06-25 — NEW → OPEN self-transition wired in the outbox relay (publish-ack)
**Status:** ✅ Back-ported 2026-06-27 → `docs/SRS_Appendix_OrderService.md` §5 (NOTE callout)
**Decision:** The `NEW → OPEN` transition is performed in `OrderOutboxRelay.relay()` immediately
after a successful Kafka publish of an `OrderPlaced` event: the relay loads the aggregate and, only
if it is still `NEW`, calls `order.markOpen()` + saves — all inside the relay's existing
`@Transactional` so `markPublished` and `markOpen` commit atomically. Guarded to skip any non-`NEW`
state (e.g. a user cancel that landed in the ~100 ms window → `CANCEL_REQUESTED`, or an already-`OPEN`
re-relay) so `markOpen()` never throws. Only `OrderPlaced` triggers it, not `OrderCancelled`.
**Why:** `SRS_Appendix_OrderService §5` defines `NEW → OPEN` as a Order-service "self-transition on
publish ack" and `SR-ORDER-EDGE-006` says the order stays `NEW` until publish succeeds — but this
transition was never implemented (`Order.markOpen()` was dead code, called only in tests). The result
was a correctness bug: passive LIMIT orders stayed `NEW` forever, so the internal open-orders
projection (default `OPEN,PARTIALLY_FILLED`) never returned them and the Matching Engine's
`IndexRebuildService` dropped them from the book on restart (`loaded=0`). The spec located the
transition at publish-ack precisely (not at persist time), so the relay — which owns the publish — is
the correct seam; doing it at persist would contradict SR-ORDER-EDGE-006 (FE shows `NEW`/"queued"
while Kafka is down).
**Where:** `services/order/.../infrastructure/outbox/OrderOutboxRelay.java` (`relay`, `markOrderOpen`);
test `OrderOutboxRelayTest`.
**Suggested doc:** SRS_Appendix_OrderService §5 — note the implementation seam (outbox relay,
post-publish, same tx) and the guard semantics (only from `NEW`; non-`NEW` skipped silently). No
behavioural change to the documented state machine; this closes a gap, not a deviation.

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

## Phase 2 — Place Order (SR-030 → SR-037)

### Error codes aligned to API_SPEC
- The scaffold's client-thrown exceptions carried generic codes; updated to the exact API_SPEC
  codes so error responses match the contract: `InsufficientBalanceException` →
  `INSUFFICIENT_AVAILABLE_BALANCE`, `MarketDataUnavailableException` → `MARKET_DATA_UNAVAILABLE`,
  `WalletUnavailableException` → `WALLET_SERVICE_UNAVAILABLE`.
- `InvalidOrderException` gained a `(code, message)` constructor so the use case can emit the
  specific per-rule codes: `INVALID_QUANTITY`, `INVALID_PRICE`, `BELOW_MIN_NOTIONAL`,
  `INVALID_SIDE`, `INVALID_ORDER_TYPE`, `LIMIT_PRICE_REQUIRED`, `LIMIT_PRICE_NOT_ALLOWED`.
  (The legacy `(message)` constructor still defaults to `INVALID_ORDER`.)
- Added `MaxOpenOrdersExceededException` (extends `ValidationException`, 400,
  `MAX_OPEN_ORDERS_EXCEEDED`). No GlobalExceptionHandler changes were needed — it already maps any
  `BaseException` generically by its declared status + errorCode.

### DTO decimal handling
- `PlaceOrderRequest` carries `quantity`/`limit_price` as `String` and parses with `BigDecimal`
  in the use case (not bean validation) so a bad decimal yields the spec code `INVALID_QUANTITY` /
  `INVALID_PRICE` rather than a generic `VALIDATION_FAILED`. HTTP-level `@NotBlank` only guards
  presence. `OrderResponse` renders all decimals via `toPlainString()`; null `limit_price` /
  `avg_fill_price` are emitted as JSON null.

### Freeze amount formula (SR-034/035)
- BUY LIMIT:  `qty × limit_price × (1 + takerRate)`, asset = quote_asset.
- BUY MARKET: `qty × best_ask × (1 + slippage) × (1 + takerRate)`, asset = quote_asset, where
  `slippage = 0.0005` (constant in the use case) and `best_ask` comes from
  `MarketDataClient.getTicker(pair)`. A null/≤0 best_ask → `MARKET_DATA_UNAVAILABLE` (503).
- SELL (LIMIT or MARKET): freeze = `qty`, asset = base_asset.
- `takerRate` is read from `appProperties.fees().takerRate()` (0.001).

### Freeze rounding scale
- Quote-asset freeze amounts are rounded to **8 dp using `RoundingMode.UP`** (constant
  `QUOTE_FREEZE_SCALE = 8`) so we never under-freeze. SELL freeze (= raw qty) is not re-scaled.
  Not dictated by spec — pick a scale safe against the wallet's quote precision; revisit if the
  wallet enforces a stricter scale.

### Freeze vs persist ordering & reconciliation
- Order of operations: validate → compute freeze → `walletClient.freeze(...)` (remote, BEFORE the
  DB transaction) → `OrderPersister.persist(...)` (one `@Transactional` saving the Order row +
  enqueuing the `OrderPlaced` outbox event). The transactional persist lives in a separate
  `OrderPersister` bean so the `@Transactional` proxy applies (self-invocation from the use case
  would bypass it).
- The freeze `referenceId` is the generated `orderId` (so freeze is idempotent and a retry is
  safe). If persist fails AFTER a successful freeze, we log an error tagged for reconciliation —
  the frozen balance is orphaned until a later phase/cron releases it. This matches the spec's
  intent that no order/event is produced on the failure path, accepting a rare orphaned-freeze
  that idempotency + reconciliation can resolve.

### Idempotency window (SR-037)
- When `client_order_id` is provided, look up an existing order by (userId, clientOrderId) and
  reject with `DUPLICATE_CLIENT_ORDER_ID` (409) if one exists within the last **24h** (matched
  against `created_at`). The DB unique index remains the ultimate safety net; the optional 60s
  app-level window was not separately implemented (24h lookup subsumes it).

### OrderPlaced event payload
- Populated from `OrderPlacedEvent(orderId, userId, pair, side, type, quantity, price, placedAt)`
  where `price = limit_price` (null for MARKET) and `placedAt = Instant.now()`. Enqueued via
  `OrderOutboxPublisher.enqueue("OrderPlaced", orderId, event)`; the relay maps that eventType to
  `kafka.orderEventsTopic`.

## Phase 3 — Cancel order + finalize events (SR-038/039/040)

### Proportional release of frozen funds & rounding
- On cancel we release only the frozen amount for the still-unfilled portion:
  `releaseAmount = freezeAmount * (quantity - filledQuantity) / quantity`, computed with
  `RoundingMode.DOWN` at scale 8. Rounding DOWN guarantees we never over-release more than was
  frozen (any sub-satoshi remainder stays frozen and is reconciled when the terminal CANCELLED
  arrives from the matching engine). In this phase there is no matching yet, so
  `filledQuantity == 0` and the formula short-circuits to the full `freezeAmount` (exact, no
  rounding). The proportional/rounded branch is implemented now so it is correct once partial
  fills exist.
- Guard cases return `BigDecimal.ZERO` (missing/zero quantity, zero-or-negative unfilled
  remainder, null freeze) so unfreeze of a fully-filled order is a no-op release.

### Unfreeze ordering: persist-then-unfreeze (opposite of place)
- PlaceOrder freezes BEFORE the DB tx (no order/event on failure). Cancel does the inverse:
  it persists CANCEL_REQUESTED + the OrderCancelled outbox event in ONE transaction, then calls
  `walletClient.unfreeze(...)` AFTER commit. Rationale: we must never release funds for a cancel
  we failed to record. The transactional persist lives in a separate `CancelOrderPersister` bean
  so the `@Transactional` proxy applies (self-invocation from the use case would bypass it).
- If the post-commit unfreeze fails, the order is already CANCEL_REQUESTED; we log an error
  tagged for reconciliation. unfreeze is idempotent by (referenceId=orderId, reason="CANCELLED"),
  so a retry/reconciliation pass is safe and will not double-release.

### State on cancel
- Cancel transitions to the intermediate `CANCEL_REQUESTED` (not terminal `CANCELLED`); the API
  response returns that state per API_SPEC. The terminal `CANCELLED` is applied later when the
  matching engine confirms removal from the book.

### Ownership vs existence
- Not-found returns 404 (`ORDER_NOT_FOUND`); an order owned by another user returns 403
  (`FORBIDDEN` via `com.haizz.exchange.common.web.ForbiddenException`). We do not mask existence
  as 404 for non-owners — 403 is returned per API_SPEC.

### OrderCancelled event payload
- `OrderCancelledEvent(orderId, userId, pair, reason="CANCELLED", cancelledAt=Instant.now())`,
  enqueued via `OrderOutboxPublisher.enqueue("OrderCancelled", orderId, event)`. The relay
  already mapped "OrderCancelled" to `kafka.orderEventsTopic` (no change needed).

## Phase 4 — Read endpoints (SR-041)

### Paged response shape — custom `PageResponse<T>` (not Spring's `Page`)
- Created `api/dto/PageResponse<T>` (record) with snake_case fields exactly per API_SPEC §3 /
  §3.7: `content`, `page`, `size`, `total_elements`, `total_pages`. Spring Data's raw `Page`
  serialization does NOT match this contract (and emits a deprecation warning), so list endpoints
  map `Page<Order>` → `PageResponse` via `PageResponse.of(page, mapper)`. NOTE: the wallet service
  returns raw `Page<T>` from its controllers — that shape differs from this spec; we deliberately
  did not mirror wallet here and instead followed the API_SPEC snake_case contract. Worth
  back-porting `PageResponse` to wallet for consistency.

### List filtering — JPA `Specification` (over finder methods)
- `OrderRepository` now also extends `JpaSpecificationExecutor<Order>`. `ListOrdersUseCase` builds
  a `Specification<Order>` that always pins `userId` and adds optional predicates for `pair`
  (equals), `state` (IN), and `createdAt` between `from`/`to`. Chosen over explicit finder methods
  because the optional-filter combinatorics would otherwise require many `findBy...` variants.

### `state` CSV parsing — reject unknown tokens (400), not skip
- `state` is split on commas, trimmed, upper-cased, and parsed to `OrderState`. An unknown token
  throws `InvalidOrderException("INVALID_STATE", ...)` → 400, rather than being silently skipped,
  so a typo surfaces to the caller instead of returning a wrong/over-broad result set. Blank/absent
  `state` means "all states" (no filter). Shared by the public list and internal endpoints
  (`ListOrdersUseCase.parseStates` is reused by `ListOpenOrdersUseCase`).

### `sort` parsing — whitelisted fields, snake_case → entity property
- `sort` (default `created_at,desc`) is parsed in the controller against a whitelist mapping
  `created_at→createdAt`, `updated_at→updatedAt`. Unknown field or direction → 400
  (`INVALID_SORT`). Whitelisting avoids exposing arbitrary entity properties / injection via the
  sort param and bridges the API's snake_case names to JPA property names.

### `from`/`to` parsing — ISO date or ISO instant
- Accepts a full ISO-8601 instant (`2026-04-01T10:00:00Z`) or a date-only value
  (`2026-04-01`, interpreted as start-of-day **UTC**). Unparseable input → 400 (`INVALID_DATE`).
  Both bounds are inclusive on `createdAt` (`>= from`, `<= to`).

### Size clamps
- Public list (`GET /api/v1/orders`): default 50, clamped to **max 500** (API_SPEC §3.4);
  size ≤ 0 falls back to the default. Internal (`GET /api/v1/orders/internal/orders`): default
  1000, clamped to **max 1000** (matches the API_SPEC §3.7 default; prevents an unbounded scan).

### Internal endpoint — path, ordering, projection
- Path is `/api/v1/orders/internal/orders` (matches the `permitAll` matcher
  `/api/v1/orders/internal/**` in SecurityConfig). API_SPEC §3.7 writes the path as
  `/internal/orders`; we use the gateway-prefixed form already permitted in this service. No JWT;
  no user filter (returns ALL users' open orders) since it feeds the Matching Engine index rebuild.
- Default `state = OPEN,PARTIALLY_FILLED`. Ordering is **FIFO (`createdAt ASC`)** — this matters
  for matching priority when the engine rebuilds its book — via repository method
  `findByStateInOrderByCreatedAtAsc`.
- Returns a compact `InternalOrderProjection` (`id, userId, pair, side, type, quantity,
  limitPrice, filledQuantity, createdAt`) with **camelCase** field names per the internal contract
  in API_SPEC §3.7 (the public `OrderResponse` stays snake_case). Decimals rendered as plain
  strings, consistent with `OrderResponse`.

### Get-one — ownership vs existence
- `GET /api/v1/orders/{orderId}`: missing → 404 (`ORDER_NOT_FOUND`); owned by another user → 403
  (`FORBIDDEN`). Existence is not masked as 404 for non-owners, consistent with the Phase 3 cancel
  decision. Read use cases are `@Transactional(readOnly = true)`.

## Phase 5 — State machine hardening + matching consumer stub + tests (SR-042)

### State machine — guarded transitions on `Order`
- The `markCancelRequested()` stub was replaced with a full set of guarded, pure-domain transition
  methods (no Spring): `applyFill(fillQty, fillPrice)`, `markOpen()`, `markCancelRequested()`,
  `markCancelled()`, `markRejected(reason)`. Each rejects an illegal transition with
  `IllegalStateException` (wrong state) or `IllegalArgumentException` (bad fill args) — kept as
  unchecked JDK exceptions because these are programmer/engine-contract errors, not user-facing
  API validation; the consumer that drives them will catch + log (fail-soft) per the wallet pattern.
- `markOpen()` is idempotent on OPEN (NEW → OPEN); any other source state throws.
- `markRejected` is only valid from NEW (admission failure), and sets `rejectionReason`.
- `markCancelRequested` now actually enforces `isCancellable()` (previously unconditional). The
  existing cancel flow (`CancelOrderPersister`) already checks `isCancellable()` before calling it,
  so runtime behaviour is unchanged; the guard is now defence-in-depth.

### Terminal precedence — FILLED wins over a pending cancel (SRS Appendix)
- `applyFill` accepts a fill from NEW / OPEN / PARTIALLY_FILLED **and CANCEL_REQUESTED**. If a fill
  arriving while CANCEL_REQUESTED completes the order it transitions to terminal `FILLED`; a
  non-completing fill goes to `PARTIALLY_FILLED` (the cancel is then resolved later by the engine).
  This encodes the rule that a completing fill takes precedence over an in-flight cancel.

### VWAP scale / rounding
- `avgFillPrice` is recomputed as the running VWAP
  `((avgFillPrice×prevFilled) + fillPrice×fillQty) / (prevFilled+fillQty)`, divided with
  `RoundingMode.HALF_UP` at **scale 18** (constant `Order.AVG_PRICE_SCALE`), matching the
  `avg_fill_price` column precision (scale 18). `applyFill` rejects overfills
  (cumulative filled > quantity) and non-positive fillQty / negative fillPrice.

### Matching-events consumer — intentional STUB
- Added `infrastructure/kafka/OrderEventConsumer` (`@KafkaListener` on
  `${order.kafka.matching-events-topic:matching.events.v1}`, groupId `order-service`) mirroring the
  wallet `WalletEventConsumer`: deserialize the `com.haizz.exchange.common.event.EventEnvelope`,
  switch on `eventType` (`OrderPartiallyFilled` / `OrderFilled` / `OrderCancelled`), catch+log any
  failure so a poison message never crashes the listener.
- Handlers delegate to a new `application/ProcessFillEventUseCase` skeleton whose bodies **log +
  `// TODO(matching)`** and do NOT mutate orders yet. Rationale: the Matching Engine is not built,
  so the concrete event shapes and the freeze-reconciliation contract are not finalised. The point
  of this phase is that the wiring exists and the app boots a consumer that no-ops cleanly when no
  events arrive. When the engine lands, each handler must, in one transaction: load the order with
  a write lock → `applyFill(...)` / `markCancelled()` → persist → release residual frozen balance.
- **Event-shape / topic ambiguity (noted for back-port):** the wallet service consumes a
  single-sided `TradeExecuted` on topic `trade.executed` (balance settlement), whereas
  `matching.events.v1` carries order-lifecycle events (`OrderPartiallyFilled` / `OrderFilled` /
  `OrderCancelled`) primarily for the gateway → FE. It is not yet decided whether the order service
  should drive fills off `matching.events.v1` (lifecycle) or a dedicated fill/trade stream. The
  stub consumes the common `event.order.*` records on `matching.events.v1` as the working
  assumption; revisit when the engine's event catalogue is finalised.

### Testability refactors (behaviour-preserving)
- Extracted the freeze formula into `domain/FreezeCalculator` (pure static `compute(...)` returning
  a `Freeze(amount, asset)` record) and the SR-033 admission rules into `domain/OrderValidator`
  (pure static `validatePriceRules` / `validateQuantity` / `validateLimitPrice` /
  `validateMinNotional` / `isMultipleOf`). `PlaceOrderUseCase` now delegates to both — the constants
  (`MARKET_SLIPPAGE=0.0005`, `QUOTE_FREEZE_SCALE=8`) and every formula/branch are byte-for-byte the
  same, so place behaviour is unchanged; the extraction exists purely so the logic is unit-testable
  without a Spring context or Docker.

### Tests — pure unit, no Spring / no Docker
- 31 tests under `src/test/java/.../domain`: `OrderStateMachineTest` (15), `FreezeCalculatorTest`
  (5), `OrderValidatorTest` (11). No `@SpringBootTest` / Testcontainers added, so
  `mvn -pl services/order -am test` is green without Docker. Verified examples: BUY 0.1 BTC @55000
  LIMIT taker 0.001 → 5505.5 quote; SELL 0.05 BTC → 0.05 base; VWAP of (0.04@55000, 0.06@56000) =
  55600; CANCEL_REQUESTED + completing fill → FILLED.

## Phase 6 — Matching-events fill consumer (SR-042)

### Residual frozen balance is OWNED by the Order service (not Wallet)
- The Matching Engine sets `residualFrozenAmount=0` on trade events, so the Wallet does NOT release
  the leftover freeze per fill. Per fill the Wallet only debits the consumed frozen portion (BUY:
  `fillQty × fillPrice` from quote-frozen; SELL: `fillQty` from base-frozen) and credits available
  minus fee. The Order service therefore owns release of the leftover freeze when an order reaches a
  TERMINAL state, because it is the only party that still knows the original `freezeAmount`.
- Residual formulas (consumed from the order's own filled portion):
  - **BUY**: `consumedQuote = filledQuantity × avgFillPrice (VWAP)`; `residual = freezeAmount − consumedQuote`
    (freezeAsset = quote). The slippage+taker buffer baked into the BUY freeze is released here.
  - **SELL**: `consumedBase = filledQuantity`; `residual = freezeAmount − filledQuantity`
    (freezeAsset = base; = 0 on a full fill, so no spurious unfreeze).
  - For a REJECTED / market-auto-cancel with 0 fills, consumed = 0 → the FULL `freezeAmount` is released.
- Residual is rounded **DOWN to 8 dp** and **clamped to ≥ 0** (never over-release; never negative on a
  pathological over-consumed input). A residual of 0 skips the unfreeze call entirely.

### Distinct unfreeze reason to guard against double-release
- The matching-driven release uses reason **`"FILL_RESIDUAL"`**, DISTINCT from the user-initiated
  DELETE path's `"CANCELLED"` (`CancelOrderUseCase.CANCELLED_REASON`). Wallet `unfreeze` is idempotent
  by `(referenceId=orderId, reason)`, so using a different reason means the matching residual release
  and the user-cancel release occupy separate idempotency keys and cannot silently cancel each other.
- Additional guard: `FillPersister` only releases when the persist actually transitioned the order
  (outcome `APPLIED`). If the order is already terminal (e.g. the user DELETE already set
  CANCEL_REQUESTED and the engine then confirms CANCELLED, or a duplicate event) the persister returns
  `SKIPPED` and NO unfreeze is attempted. So the two paths release at most once each, for their own
  distinct portions, and replays are no-ops.

### Cumulative→delta idempotency
- Matching `OrderPartiallyFilledEvent.filledQuantity` and `OrderFilledEvent.filledQuantity` are
  CUMULATIVE, but `Order.applyFill` takes a DELTA. The conversion `delta = eventCumulative −
  order.filledQuantity` is done **inside the `@Transactional` persister, under the pessimistic
  write-lock**, where the current filled quantity is authoritative. A `delta <= 0` is treated as an
  idempotent replay / superseded out-of-order event and is SKIPPED (no mutation, no save). `onFilled`
  applies the remaining delta to drive the state to FILLED; if already FILLED it is a no-op.

### Order-missing tolerance (MVP)
- A fill/cancel event may arrive before the local order row is visible (event ordering vs. the
  place-order commit). `FillPersister` returns `MISSING` and the use case logs a WARN and skips —
  acceptable for the MVP. (A future hardening could DLQ/retry the event.) MISSING never triggers an
  unfreeze.

### Transaction boundary & post-commit unfreeze (mirrors CancelOrderUseCase)
- New `application/FillPersister` `@Component` holds the three `@Transactional` methods
  (`applyPartial` / `complete` / `cancel`) that load (pessimistic lock) → mutate → save, returning an
  immutable `FillResult` snapshot (outcome + userId/side/freezeAmount/freezeAsset/filledQuantity/
  avgFillPrice) captured as values so it is safe to read after the tx commits. `ProcessFillEventUseCase`
  performs the wallet `unfreeze` **AFTER** the persist commits — same persist-then-unfreeze ordering as
  cancel, so funds are never released for a state we failed to record. Separate bean so the
  `@Transactional` proxy applies (self-invocation would bypass it). On unfreeze failure the state is
  already persisted; we log for reconciliation (idempotent retry is safe).

### Tests
- 20 new pure-unit tests (Mockito, no Spring/Docker): `FillPersisterTest` (8 — delta conversion,
  replay/terminal/missing skips, complete→FILLED idempotency, cancel transition+terminal guard) and
  `ProcessFillEventUseCaseTest` (12 — partial passes cumulative & never unfreezes, FILLED BUY residual
  = freeze−filled×avg verified via Mockito captor (5505.5−5500=5.5), SELL full fill → residual 0 → no
  unfreeze, REJECTED 0-fill releases full freeze, MARKET_PARTIAL releases freeze−consumed BUY & SELL,
  terminal-skip → no unfreeze, residual never negative, rounds DOWN 8dp). Total order module: 51 tests.
