# SRS Appendix — Order Service

**Parent Document:** `SRS.md` v1.0
**Service:** Order Service
**Status:** Design complete, implementation not started
**Owned Entities:** `Order`, `Asset` (reference), `TradingPair` (reference), `FeeSchedule` (reference)

---

## 1. Purpose & Boundaries

The Order Service is the write-side entry point for all user trading intent. It is responsible for validating incoming orders against business rules, freezing balance through the Wallet Service, persisting order state, and publishing order lifecycle events to Kafka.

### 1.1 Service Responsibilities

- Accept order placement (market, limit) and cancellation requests.
- Validate against pair metadata (tick size, step size, min notional, enabled flag).
- Enforce idempotency via `client_order_id`.
- Orchestrate the freeze-then-persist sequence with Wallet Service (synchronous HTTP).
- Persist order state and consume order lifecycle events from Matching Engine to update state.
- Publish `OrderPlaced` and `OrderCancelRequested` events on Kafka.
- Expose paginated read APIs for order history.
- Own reference data for assets, trading pairs, and fee schedules (MVP simplification; may be extracted post-MVP per BRD learnings).

### 1.2 Service Non-Responsibilities

- Matching / execution logic (Matching Engine).
- Balance mutations (Wallet Service).
- Market data / price quotation (Market Data Service).

### 1.3 Bounded Context

The Order Service's bounded context is **"Order intent and lifecycle"**. Downstream events (fills, cancellations) are facts produced by other services that this service reacts to — the service does not compute them.

---

## 2. Domain Model

### 2.1 Aggregate — Order

```
Order (aggregate root)
├── id: UUID (PK)
├── client_order_id: UUID (nullable, unique per user per 24h)
├── user_id: UUID
├── pair: String (e.g., "BTCUSDT")
├── side: Enum [BUY, SELL]
├── type: Enum [MARKET, LIMIT]
├── quantity: BigDecimal(36, 18)         -- total requested qty in base asset
├── limit_price: BigDecimal(36, 18) NULL  -- required for LIMIT, null for MARKET
├── time_in_force: Enum [GTC]             -- MVP: GTC only
├── state: Enum [NEW, OPEN, PARTIALLY_FILLED, FILLED, CANCELLED, REJECTED]
├── filled_qty: BigDecimal(36, 18)        -- default 0; updated from Matching Engine events
├── avg_fill_price: BigDecimal(36, 18) NULL
├── freeze_amount: BigDecimal(36, 18)     -- amount actually frozen on placement
├── freeze_asset: String                   -- "USDT" for BUY, base symbol for SELL
├── rejection_reason: String NULL          -- populated when state = REJECTED
├── version: Long                          -- optimistic locking
├── created_at: Timestamp
└── updated_at: Timestamp
```

**Invariants:**

- `filled_qty ≤ quantity` at all times.
- `state ∈ {FILLED}` iff `filled_qty = quantity`.
- `limit_price` is required when `type = LIMIT`, must be null when `type = MARKET`.
- `avg_fill_price` is null when `filled_qty = 0`, else = `Σ(fill_price × fill_qty) / filled_qty`.
- Terminal states (`FILLED`, `CANCELLED`, `REJECTED`) are never updated after first entry.

### 2.2 Reference Data — TradingPair

```
TradingPair
├── symbol: String (PK, e.g., "BTCUSDT")
├── base_asset: String (FK to Asset, e.g., "BTC")
├── quote_asset: String (FK to Asset, e.g., "USDT")
├── tick_size: BigDecimal            -- from Binance exchangeInfo, cached via Market Data Service
├── step_size: BigDecimal            -- from Binance exchangeInfo
├── min_notional: BigDecimal         -- hard-coded per-pair in MVP (10 USDT flat)
├── enabled: Boolean
└── updated_at: Timestamp
```

Seeded on startup from config + pulled from Market Data Service's cached `exchangeInfo`.

### 2.3 Reference Data — Asset

```
Asset
├── symbol: String (PK, e.g., "USDT")
├── name: String (e.g., "Tether")
├── decimals: Integer (e.g., 6 for USDT, 8 for BTC)
└── enabled: Boolean
```

MVP seed: `USDT, BTC, ETH, BNB, SOL, XRP`.

### 2.4 Reference Data — FeeSchedule

```
FeeSchedule
├── tier: String (PK, e.g., "tier_0")
├── maker_rate: BigDecimal (e.g., 0.0010)
├── taker_rate: BigDecimal (e.g., 0.0010)
└── active: Boolean
```

MVP seed: single row `tier_0` with rates 0.0010 / 0.0010.

---

## 3. API Specifications

### 3.1 REST Endpoints (Consumer: API Gateway → FE)

All endpoints require `Authorization: Bearer <JWT>`; authenticated `user_id` is extracted from the token.

#### 3.1.1 Place Order

```
POST /orders
Content-Type: application/json

Body:
{
  "client_order_id": "550e8400-e29b-41d4-a716-446655440000",  // optional, UUID v4
  "pair": "BTCUSDT",
  "side": "BUY",                        // BUY | SELL
  "type": "LIMIT",                      // MARKET | LIMIT
  "quantity": "0.1",                    // decimal string
  "limit_price": "55000.00",            // required for LIMIT, omitted for MARKET
  "time_in_force": "GTC"                // MVP: GTC only, default GTC
}

Responses:
  201 Created
  {
    "order_id": "7d3e...",
    "client_order_id": "550e8400-...",
    "state": "NEW",
    "pair": "BTCUSDT",
    "side": "BUY",
    "type": "LIMIT",
    "quantity": "0.1",
    "limit_price": "55000.00",
    "filled_qty": "0",
    "created_at": "2026-04-20T10:12:34.567Z"
  }

  400 Bad Request — validation errors (codes below)
  401 Unauthorized
  409 Conflict — DUPLICATE_CLIENT_ORDER_ID
  503 Service Unavailable — MARKET_DATA_UNAVAILABLE, WALLET_SERVICE_UNAVAILABLE, KAFKA_UNAVAILABLE
```

**Error codes:**

| Code | HTTP | Meaning |
|------|------|---------|
| `PAIR_NOT_SUPPORTED` | 400 | Pair not in enabled set. |
| `INVALID_QUANTITY` | 400 | Quantity ≤ 0 or not a multiple of step_size. |
| `INVALID_PRICE` | 400 | Limit price ≤ 0 or not a multiple of tick_size. |
| `BELOW_MIN_NOTIONAL` | 400 | Price × qty < min_notional. |
| `INVALID_SIDE` | 400 | Side not BUY/SELL. |
| `INVALID_ORDER_TYPE` | 400 | Type not MARKET/LIMIT. |
| `LIMIT_PRICE_REQUIRED` | 400 | Limit order without limit_price. |
| `LIMIT_PRICE_NOT_ALLOWED` | 400 | Market order with limit_price. |
| `INSUFFICIENT_AVAILABLE_BALANCE` | 400 | Freeze failed at Wallet Service. |
| `MAX_OPEN_ORDERS_EXCEEDED` | 400 | User has ≥ 100 open orders on this pair. |
| `DUPLICATE_CLIENT_ORDER_ID` | 409 | Same client_order_id used within 24h. |
| `MARKET_DATA_UNAVAILABLE` | 503 | Pair feed stale > 10s. |

#### 3.1.2 Cancel Order

```
DELETE /orders/{order_id}

Responses:
  200 OK
  {
    "order_id": "7d3e...",
    "state": "CANCEL_REQUESTED"   // final state set asynchronously by Matching Engine
  }

  403 Forbidden — FORBIDDEN (not owner)
  404 Not Found — ORDER_NOT_FOUND
  409 Conflict — ORDER_NOT_CANCELLABLE (terminal state)
```

> Note: The DELETE endpoint returns 200 with intermediate state `CANCEL_REQUESTED`. The final `CANCELLED` state is set when Matching Engine publishes `OrderCancelled` and Order Service consumes it. The FE observes the final state via WebSocket push.

#### 3.1.3 Get Order by ID

```
GET /orders/{order_id}

Responses:
  200 OK — full order snapshot (same shape as placement response + filled_qty, avg_fill_price, state)
  403 Forbidden — not owner
  404 Not Found
```

#### 3.1.4 List Orders

```
GET /orders?pair=BTCUSDT&state=OPEN&from=2026-04-01&to=2026-04-20&page=0&size=50&sort=created_at,desc

Query params:
  pair: optional String
  state: optional String or comma-separated list (e.g., "OPEN,PARTIALLY_FILLED")
  from: optional ISO date
  to: optional ISO date
  page: default 0
  size: default 50, max 500
  sort: default "created_at,desc"

Responses:
  200 OK
  {
    "content": [ {Order}, ... ],
    "page": 0,
    "size": 50,
    "total_elements": 120,
    "total_pages": 3
  }
```

### 3.2 Kafka Events

**Produced:**

- `orders.events.v1` topic:
  - `OrderPlaced` — on successful order persistence.
  - `OrderCancelRequested` — on user cancel request.

**Consumed:**

- `matching.events.v1` topic:
  - `OrderPartiallyFilled` → update filled_qty, avg_fill_price, state = PARTIALLY_FILLED.
  - `OrderFilled` → update filled_qty = quantity, state = FILLED.
  - `OrderCancelled` → state = CANCELLED, record final filled_qty.

Consumer group: `order-service`. Partition key: `order_id` (for per-order ordering).

### 3.3 Outgoing Synchronous Calls

- `Wallet Service — POST /internal/wallets/freeze` — synchronous, required before `OrderPlaced` publication.
- `Market Data Service — GET /internal/ticker/{pair}` — synchronous, used to compute freeze amount for market orders.
- `Market Data Service — GET /internal/pairs/{pair}/metadata` — synchronous, cached locally with 5-minute TTL.

---

## 4. Functional Requirements (Detailed)

Inherits and expands SRS §3.3.

### SR-ORDER-AP-001 — Placement Orchestration Sequence

**Requirement:** Order placement must follow the canonical sequence:

1. HTTP-level validation (required fields, types).
2. Business validation (pair enabled, quantity multiple of step size, price multiple of tick size, notional ≥ min_notional, user not exceeding max open orders on pair).
3. Check `client_order_id` idempotency (query recent orders by user + client_order_id within 24h).
4. For market orders: query Market Data Service for current best bid/ask.
5. Compute freeze amount per BRL-FREEZE-001/002/003.
6. Call Wallet Service `POST /internal/wallets/freeze` synchronously.
7. On successful freeze: persist Order with state `NEW`, write to outbox table in the same DB transaction.
8. Commit DB transaction.
9. Outbox relay publishes `OrderPlaced` to Kafka (at-least-once delivery).
10. Return `201 Created` to caller with state `NEW`.

**Acceptance Criteria:**
- **Given** all validations pass and freeze succeeds, **when** the order is placed, **then** the Order row exists with state `NEW` AND an outbox row exists in the same transaction.
- **Given** Wallet Service returns 400 INSUFFICIENT_AVAILABLE_BALANCE, **when** the placement is processed, **then** no Order row is persisted, no Kafka event is published, and caller receives 400.
- **Given** the DB commit fails after freeze succeeds, **when** the failure occurs, **then** a compensation (unfreeze) is issued to Wallet Service. If the compensation fails, the failure is logged with sufficient context for manual recovery, and the incident is tracked in a `wallet_reconciliation` queue (see §7 Operational Concerns).

### SR-ORDER-AP-002 — Transactional Outbox Pattern

**Requirement:** Order persistence and Kafka event publication must be decoupled via an outbox table to ensure exactly-once semantics despite the dual-write problem.

Implementation:

- `order_outbox` table: `id, aggregate_id, event_type, payload_json, created_at, published_at (nullable), attempts`.
- Outbox rows are inserted in the same transaction as the Order row.
- A background job (polling every 100ms, or Debezium-style CDC post-MVP) reads unpublished rows, publishes to Kafka, and updates `published_at`.
- Duplicates downstream are tolerated via event idempotency (SR-MATCH-011 in Matching Engine appendix).

**Acceptance Criteria:**
- **Given** an order is successfully placed, **when** the outbox relay runs, **then** exactly one `OrderPlaced` event with `event_id = order_outbox.id` is published to Kafka.
- **Given** the outbox relay crashes between publish and marking `published_at`, **when** it restarts, **then** the event is re-published; the downstream consumer deduplicates by `event_id`.

### SR-ORDER-AP-003 — Idempotency via `client_order_id`

**Requirement:** When the client provides a `client_order_id`, duplicate submissions within 24 hours must be rejected.

**Implementation:** Unique index on `(user_id, client_order_id)`. On insert conflict, return the existing order's state rather than creating a new one, with HTTP 409 `DUPLICATE_CLIENT_ORDER_ID`.

> **NOTE (back-ported 2026-06-17 from services/order/DECISIONS.md):** As built, the use case
> looks up an existing order by `(userId, clientOrderId)` within the last **24h** (matched on
> `created_at`) and rejects with 409 `DUPLICATE_CLIENT_ORDER_ID`; the DB unique index is the
> ultimate safety net. The optional **60s app-level dedup window** referenced for SR-037 was
> **not separately implemented** — the 24h lookup subsumes it.

**Acceptance Criteria:**
- **Given** user submits `POST /orders {client_order_id: "abc-123", ...}` successfully at T0, **when** the same user submits identical payload at T0 + 5 minutes, **then** the response is `409 Conflict` with body including the original `order_id` and state.
- **Given** a client_order_id was used 25 hours ago, **when** the same ID is submitted again, **then** it is accepted as a new order (24h dedup window).

### SR-ORDER-AP-004 — Cancellation Semantics

**Requirement:** Cancellation is a two-phase operation: the service accepts the request (publishing `OrderCancelRequested`) and later confirms the cancellation when Matching Engine emits `OrderCancelled`.

**Acceptance Criteria:**
- **Given** an order in state `OPEN`, **when** user cancels, **then** HTTP 200 is returned immediately with state `CANCEL_REQUESTED`, a `OrderCancelRequested` event is published, and the Order row transitions `OPEN → CANCEL_REQUESTED` (intermediate state in the state machine — see §5).
- **Given** Matching Engine publishes `OrderCancelled` shortly after, **when** Order Service consumes it, **then** state transitions `CANCEL_REQUESTED → CANCELLED` and the final `filled_qty` is written.
- **Given** a fill occurs between `OrderCancelRequested` and `OrderCancelled` (race condition), **when** both events arrive, **then** the partial fill is applied first (filled_qty updated) and the cancellation records the remaining qty as cancelled. The final state is `CANCELLED` with `filled_qty < quantity`.

> **NOTE (back-ported 2026-06-17 from services/order/DECISIONS.md):** Cancel transitions to the
> intermediate `CANCEL_REQUESTED` (never directly to terminal `CANCELLED`), then unfreezes only
> the still-unfilled portion:
> `releaseAmount = freezeAmount × (quantity − filledQuantity) / quantity`, computed with
> `RoundingMode.DOWN` at scale 8 (never over-release; any sub-unit remainder stays frozen and is
> reconciled when the terminal `CANCELLED` arrives). The cancel persist (state +
> `OrderCancelRequested` outbox) and the wallet unfreeze are ordered **persist-then-unfreeze**
> (one DB tx commits first, unfreeze after commit) — the inverse of placement's
> freeze-then-persist — so funds are never released for a cancel that wasn't recorded. The
> unfreeze is idempotent by `(referenceId = orderId, reason = "CANCELLED")`.

### SR-ORDER-AP-005 — Freeze Amount Computation

Full specification of BRL-FREEZE rules with worked examples.

**BUY LIMIT:**
```
freeze_amount = quantity × limit_price × (1 + taker_fee_rate)
freeze_asset  = quote_asset (e.g., USDT)
```
Example: BUY 0.1 BTC @ 55000 USDT, fee 0.001 → freeze = 0.1 × 55000 × 1.001 = **5,505.50 USDT**.

**BUY MARKET:**
```
best_ask       = Market Data Service current best ask
freeze_amount  = quantity × best_ask × (1 + slippage) × (1 + taker_fee_rate)
freeze_asset   = quote_asset
```
Example: BUY 0.1 BTC, best_ask = 60000 USDT, slippage 0.0005, fee 0.001 → freeze = 0.1 × 60000 × 1.0005 × 1.001 = **6,006.03 USDT**.

> **Safety margin rationale:** Actual fill price may be slightly worse due to walk-the-book. If actual freeze is insufficient, the settlement would fail. The (1 + slippage) factor provides buffer. Any unused frozen amount is refunded on fill via `WalletUnfrozen`.

**SELL (LIMIT or MARKET):**
```
freeze_amount = quantity
freeze_asset  = base_asset (e.g., BTC)
```
Example: SELL 0.05 ETH → freeze = **0.05 ETH**. Fee is deducted from received quote asset at settlement, not pre-frozen.

> **NOTE (back-ported 2026-06-17 from services/order/DECISIONS.md):** `taker_fee_rate = 0.001`
> and `slippage = 0.0005` are the implemented constants. For MARKET BUY, `best_ask` comes from
> the Market Data ticker; a null/≤0 best ask yields `MARKET_DATA_UNAVAILABLE` (503). The
> **quote-asset** freeze amount is rounded to **8 dp using `RoundingMode.UP`** so the order never
> under-freezes; the SELL freeze (= raw `quantity`) is not re-scaled.

**Acceptance Criteria:**
- **Given** a BUY LIMIT order for 0.5 BTC @ 58,000 USDT is submitted, **when** the freeze is computed, **then** `freeze_amount = 0.5 × 58000 × 1.001 = 29,029 USDT` is sent to Wallet Service.
- **Given** a BUY MARKET order for 0.1 BTC with best_ask = 60000, **when** the freeze is computed, **then** `freeze_amount = 6006.03 USDT`.
- **Given** a SELL order for 0.05 BTC at any price, **when** the freeze is computed, **then** `freeze_amount = 0.05 BTC` in base asset.

### SR-ORDER-AP-006 — Max Open Orders Limit

**Requirement:** A user may have at most 100 open orders (states `OPEN`, `PARTIALLY_FILLED`, `CANCEL_REQUESTED`) per pair.

**Acceptance Criteria:**
- **Given** user has 100 open orders on BTC/USDT, **when** they place a 101st, **then** response is `400` with code `MAX_OPEN_ORDERS_EXCEEDED`, and the existing freeze is not released (validation happens before freeze).
- **Given** user has 100 open orders on BTC/USDT and 50 on ETH/USDT, **when** they place a new ETH/USDT order, **then** it is accepted (limit is per pair).

### SR-ORDER-AP-007 — Market Data Freshness Gate

**Requirement:** Before accepting any order, Order Service must verify the pair's market data is fresh (last update ≤ 10 seconds).

**Implementation:** Order Service caches pair health status updated via consumption of `MarketDataFeedDegraded` events and periodic (5s) poll of `/internal/market-data/health`.

**Acceptance Criteria:**
- **Given** `BTCUSDT` is marked degraded, **when** a new order for that pair is placed, **then** response is `503` with code `MARKET_DATA_UNAVAILABLE`.
- **Given** feed recovers and `MarketDataFeedRecovered` event is consumed, **when** subsequent orders on that pair are placed, **then** they succeed.

---

## 5. State Machine — Order

Extended from SRS Appendix B. Introduces the `CANCEL_REQUESTED` intermediate state used by this service (not exposed to external callers beyond the DELETE response).

```
NEW ──→ OPEN ──→ PARTIALLY_FILLED ──→ FILLED
         │           │
         │           └──→ CANCEL_REQUESTED ──→ CANCELLED
         │
         └──→ CANCEL_REQUESTED ──→ CANCELLED

NEW ──→ REJECTED  (only if validation somehow fails post-commit, rare)
```

**State transition rules:**

| From | Event | To | Service |
|------|-------|----|--------:|
| `NEW` | Freeze succeeded, row persisted, event published | `OPEN` | Order Service (self-transition on publish ack) |
| `OPEN` | `OrderPartiallyFilled` consumed | `PARTIALLY_FILLED` | Order Service (consumer) |
| `PARTIALLY_FILLED` | `OrderPartiallyFilled` consumed | `PARTIALLY_FILLED` (self-loop, update counts) | Order Service |
| `OPEN` or `PARTIALLY_FILLED` | `OrderFilled` consumed | `FILLED` | Order Service |
| `OPEN` or `PARTIALLY_FILLED` | User DELETE | `CANCEL_REQUESTED` | Order Service |
| `CANCEL_REQUESTED` | `OrderCancelled` consumed | `CANCELLED` | Order Service |
| Any terminal (`FILLED`, `CANCELLED`, `REJECTED`) | Any | ❌ illegal | — |

**Note on `CANCEL_REQUESTED`:** The SRS main doc (§Appendix B) collapses this into `OPEN`/`PARTIALLY_FILLED` for simplicity. In this service's implementation, it is a distinct state to prevent duplicate cancel requests and clarify UI feedback.

> **NOTE (back-ported 2026-06-17 from services/order/DECISIONS.md):** As built, `applyFill`
> accepts a fill from `NEW` / `OPEN` / `PARTIALLY_FILLED` **and `CANCEL_REQUESTED`**:
> - **Terminal precedence** — a fill arriving while `CANCEL_REQUESTED` that **completes** the
>   order transitions to terminal `FILLED` (FILLED wins over the in-flight cancel); a
>   non-completing fill goes to `PARTIALLY_FILLED` and the cancel resolves later.
> - **`avg_fill_price`** is the running VWAP
>   `((avg×prevFilled) + fillPrice×fillQty) / (prevFilled + fillQty)`, divided with
>   `RoundingMode.HALF_UP` at **scale 18** (matching the `avg_fill_price` column precision).
>   `applyFill` rejects overfills (cumulative filled > quantity) and non-positive fill quantity /
>   negative fill price as programmer/engine-contract errors.

---

## 6. Data Model & Persistence

### 6.1 Tables

```sql
CREATE TABLE orders (
  id                UUID PRIMARY KEY,
  client_order_id   UUID NULL,
  user_id           UUID NOT NULL,
  pair              VARCHAR(20) NOT NULL,
  side              VARCHAR(4) NOT NULL,      -- BUY/SELL
  type              VARCHAR(6) NOT NULL,      -- MARKET/LIMIT
  quantity          NUMERIC(36, 18) NOT NULL,
  limit_price       NUMERIC(36, 18) NULL,
  time_in_force     VARCHAR(3) NOT NULL DEFAULT 'GTC',
  state             VARCHAR(20) NOT NULL,
  filled_qty        NUMERIC(36, 18) NOT NULL DEFAULT 0,
  avg_fill_price    NUMERIC(36, 18) NULL,
  freeze_amount     NUMERIC(36, 18) NOT NULL,
  freeze_asset      VARCHAR(10) NOT NULL,
  rejection_reason  TEXT NULL,
  version           BIGINT NOT NULL DEFAULT 0,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX ux_orders_client_order_id_recent
  ON orders (user_id, client_order_id)
  WHERE client_order_id IS NOT NULL AND created_at > NOW() - INTERVAL '24 hours';
  -- Note: "recent" predicate indexes can be maintained via scheduled reindex;
  -- alternative is a separate client_order_id_dedup table with TTL.

CREATE INDEX ix_orders_user_state ON orders (user_id, state);
CREATE INDEX ix_orders_user_pair_created ON orders (user_id, pair, created_at DESC);

CREATE TABLE order_outbox (
  id            UUID PRIMARY KEY,
  aggregate_id  UUID NOT NULL,      -- order_id
  event_type    VARCHAR(40) NOT NULL,
  payload_json  JSONB NOT NULL,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  published_at  TIMESTAMPTZ NULL,
  attempts      INT NOT NULL DEFAULT 0
);
CREATE INDEX ix_outbox_unpublished ON order_outbox (created_at) WHERE published_at IS NULL;

CREATE TABLE trading_pairs (
  symbol         VARCHAR(20) PRIMARY KEY,
  base_asset     VARCHAR(10) NOT NULL,
  quote_asset    VARCHAR(10) NOT NULL,
  tick_size      NUMERIC(36, 18) NOT NULL,
  step_size      NUMERIC(36, 18) NOT NULL,
  min_notional   NUMERIC(36, 18) NOT NULL,
  enabled        BOOLEAN NOT NULL DEFAULT TRUE,
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE assets (
  symbol    VARCHAR(10) PRIMARY KEY,
  name      VARCHAR(40) NOT NULL,
  decimals  INT NOT NULL,
  enabled   BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE fee_schedules (
  tier         VARCHAR(20) PRIMARY KEY,
  maker_rate   NUMERIC(10, 6) NOT NULL,
  taker_rate   NUMERIC(10, 6) NOT NULL,
  active       BOOLEAN NOT NULL DEFAULT TRUE
);
```

**Precision:** `NUMERIC(36, 18)` accommodates BTC's 8-decimal precision and USDT's 6-decimal precision with margin. Do not use `FLOAT` or `DOUBLE` for monetary values.

### 6.2 Optimistic Locking

The `version` column is incremented on every update. Spring Data JPA `@Version` annotation handles the optimistic lock check and throws `OptimisticLockingFailureException` on conflict, which is caught by the consumer and retried up to 3 times. Persistent conflict after 3 attempts logs a warning and fails the event processing (Kafka will retry via consumer group offset).

---

## 7. Operational Concerns

### 7.1 Reconciliation — Orphan Freezes

**Problem:** If Order Service freezes balance but fails to persist (or fails to publish `OrderPlaced`), the Wallet has a frozen amount with no corresponding order. This is a soft correctness bug that the outbox pattern plus compensating unfreeze should prevent, but monitoring is required.

**Mitigation:**

- Periodic job (hourly): query Wallet Service for all frozen amounts with `reference_type = ORDER`, compare with Order Service for matching open orders. Any frozen amount without a matching order (state not terminal) is flagged for manual reconciliation.
- In MVP, flagged items are logged; a cleanup admin endpoint (`POST /admin/orders/unfreeze-orphan`) can be invoked manually.
- Post-MVP: automatic reconciliation worker.

### 7.2 Observability

- Metrics: `orders.placed.count`, `orders.rejected.count` (tagged by error code), `orders.cancelled.count`, `order.placement.duration` histogram, `outbox.unpublished.count` gauge.
- Structured logs with `order_id`, `user_id`, `pair`, `correlation_id` fields.
- `/actuator/health` checks: DB connectivity, Wallet Service reachability, Market Data Service reachability, Kafka producer health.

### 7.3 Rate Limiting

Per SR-NFR-SEC-007 style: max 10 order placements per second per user. Implemented at API Gateway via Redis-backed rate limiter; exceeding returns `429 Too Many Requests`. This is primarily abuse prevention; legitimate learners will rarely approach this limit.

---

## 8. Edge Cases & Error Handling

| ID | Scenario | Required Behavior |
|----|----------|-------------------|
| SR-ORDER-EDGE-001 | User places a market order when best_ask is 0 or missing from Market Data. | Reject with `MARKET_DATA_UNAVAILABLE`. Market Data is expected to have been marked degraded per SR-MATCH-012 before this state is reached. |
| SR-ORDER-EDGE-002 | User places a LIMIT BUY at a price above current best ask (crossing the spread). | Accept. The limit will match immediately on the next external trade at that price or lower. Slippage does NOT apply to limit orders. |
| SR-ORDER-EDGE-003 | User places a LIMIT SELL at a price below current best bid (crossing the spread). | Accept. Same logic — fills on next touch. |
| SR-ORDER-EDGE-004 | Two users simultaneously place orders using the same `client_order_id`. | Unique index enforces user-level uniqueness only; different users with same UUID are both accepted. (No leak: client_order_id is not exposed across users.) |
| SR-ORDER-EDGE-005 | Wallet Service is down when placement is attempted. | Return `503 Service Unavailable` with code `WALLET_SERVICE_UNAVAILABLE`. No freeze, no order persisted. |
| SR-ORDER-EDGE-006 | Kafka is down when outbox relay attempts publish. | Relay retries with exponential backoff. Order state remains `NEW` in DB. The order does NOT transition to `OPEN` until publish succeeds — the FE sees state `NEW` and may display "queued" or similar UI. |
| SR-ORDER-EDGE-007 | `OrderFilled` arrives before `OrderPartiallyFilled` due to Kafka reorder (unlikely with per-order partitioning, but possible cross-partition). | Order Service handles by tracking highest `filled_qty` seen; the one with higher `filled_qty` wins. State is computed from `filled_qty == quantity` check. |
| SR-ORDER-EDGE-008 | A fill arrives for an order already in `CANCELLED` state (race). | Apply the fill (update `filled_qty`, publish derived `WalletSettle` via Kafka for Wallet to credit/debit). State stays `CANCELLED` with `filled_qty` reflecting actual execution. This is unusual but not an error — Matching Engine is the arbiter of fills. |
| SR-ORDER-EDGE-009 | User attempts to cancel their own order twice quickly. | First cancel: state → `CANCEL_REQUESTED`, event published. Second cancel: returns `409` with `ORDER_NOT_CANCELLABLE` (state is `CANCEL_REQUESTED`, not `OPEN`). |
| SR-ORDER-EDGE-010 | Order Service restarts with unpublished outbox rows. | Outbox relay resumes and publishes. Events are idempotent via `event_id`. |

---

## 9. Traceability

| Order Service Requirement | Parent SRS Requirement(s) | BRD Requirement(s) |
|---------------------------|--------------------------|--------------------|
| SR-ORDER-AP-001 | SR-ORDER-008, SR-ORDER-010 | BR-004, BR-005 |
| SR-ORDER-AP-002 | SR-EDGE-003 | NFR-005, NFR-006 |
| SR-ORDER-AP-003 | SR-ORDER-007 | NFR-005 |
| SR-ORDER-AP-004 | SR-ORDER-012, SR-ORDER-013 | BR-006 |
| SR-ORDER-AP-005 | BRL-FREEZE-001/002/003 | BR-004, BR-005 |
| SR-ORDER-AP-006 | SR-EDGE-009 | — (new constraint) |
| SR-ORDER-AP-007 | SR-EDGE-001, SR-MATCH-012 | NFR-013 |

---

## 10. Implementation Notes for Coding Agent

Non-binding guidance. Decisions already made by Haizz elsewhere take precedence.

- **Spring Boot module structure:** `order-service` Maven module; package root `com.haizz.exchange.order`. Sub-packages: `api` (controllers, DTOs), `domain` (entities, value objects), `infrastructure` (JPA repositories, Kafka producers/consumers, outbox relay), `application` (use cases / services).
- **Transactional boundaries:** Use `@Transactional` on the placement use case wrapping: idempotency check → validation → freeze call → order insert + outbox insert. The freeze call is OUTSIDE the DB transaction but BEFORE the insert; a failed freeze aborts before any DB write.
- **Outbox relay:** A `@Scheduled(fixedDelay = 100)` method polling `order_outbox WHERE published_at IS NULL ORDER BY created_at LIMIT 100`. Publish and mark published. Cap attempts at 10; after cap, move to `order_outbox_dead_letter` for manual inspection.
- **DTO validation:** Use `jakarta.validation` annotations on request DTOs. Custom validators for `quantity is multiple of step_size` will need the pair context, so do it in the service layer, not at annotation level.
- **Testing:** Unit tests for validation logic (table-driven for tick_size/step_size edge cases), integration tests using Testcontainers (Postgres + Kafka) for the full placement orchestration, and a dedicated test class for the state machine transitions.

---

*End of `SRS_Appendix_OrderService.md`.*
