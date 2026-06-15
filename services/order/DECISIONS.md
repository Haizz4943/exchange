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
