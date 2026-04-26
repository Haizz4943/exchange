# SRS Appendix — Matching Engine

**Parent Document:** `SRS.md` v1.0
**Service:** Matching Engine
**Status:** Design complete, implementation not started
**Owned Entities:** `Trade`

---

## 1. Purpose & Boundaries

The Matching Engine is the simulation arbiter of trade execution. It does **not** perform peer-to-peer matching between platform users; instead, it simulates fills against live Binance market data. Its outputs — `TradeExecuted`, `OrderPartiallyFilled`, `OrderFilled`, `OrderCancelled` events — are the source of truth for trade activity in the system.

This service is the one that most directly reflects the "simulation" nature of the platform. Its design is intentionally swappable: a future P2P Matching Engine variant could replace this one by consuming the same input events and producing the same output events. See BRL-MATCH-001 and the note in BRD Section 8.

### 1.1 Service Responsibilities

- Consume `OrderPlaced` events — register limit orders in an in-memory open-orders index; execute market orders immediately using walk-the-book against Binance depth snapshots.
- Consume `OrderCancelRequested` events — remove the order from the open-orders index, emit `OrderCancelled`.
- Consume `ExternalTradeObserved` events — for each external trade, evaluate open limit orders on the same pair for fill eligibility and emit `TradeExecuted` events for matching fills (FIFO by `created_at`).
- Compute fees per SR-MATCH-009/010.
- Maintain idempotency keyed on incoming event IDs.
- Publish execution events atomically with respect to the state machine.

### 1.2 Service Non-Responsibilities

- Persisting the `Order` aggregate (Order Service owns Order state).
- Mutating balances (Wallet Service consumes this service's events and does the mutations).
- Providing depth or ticker data (Market Data Service).
- Order validation (already done by Order Service before `OrderPlaced` is emitted).

### 1.3 Bounded Context

"Execution simulation." The engine answers: given this order and the current external market, what fills happen, when, at what price, with what fee?

---

## 2. Core Concepts

### 2.1 The Open-Orders Index

An in-memory data structure holding all currently-fillable limit orders, keyed primarily by `pair` and secondarily organized for efficient touch-detection.

**Required operations:**

- `add(order)` — O(log n) insert.
- `remove(order_id)` — O(log n) removal.
- `findEligibleFills(pair, external_price, side)` — return all orders on the pair whose `limit_price` satisfies the touch condition, in FIFO (`created_at`) order.
- `updateRemaining(order_id, remaining_qty)` — O(1) update; if remaining reaches 0, remove.

**MVP implementation:** Per-pair per-side `TreeMap<BigDecimal limitPrice, TreeMap<Long createdAtMicros, Order>>` or equivalent. At MVP scale (max ~500 open orders per pair) a simpler linked-list scan is acceptable; optimize only if load tests demand it.

**Persistence and recovery:** The index is rebuilt on service startup by querying Order Service for all orders in states `OPEN` and `PARTIALLY_FILLED`. This makes the service stateful but recoverable. See §7.1 for startup sequence.

### 2.2 The External Trade Buffer

A short-lived buffer of recent external trades per pair, to handle late-arriving open orders that should have matched against a recent external trade.

**MVP rule:** Buffer size = trades within the last **5 seconds** per pair. New `OrderPlaced` events for limit orders whose limit is within the recent trade range **do not** retroactively fill from the buffer — the buffer is for a different purpose: it smooths over the race between `OrderPlaced` consumption and `ExternalTradeObserved` consumption (see SR-MATCH-EDGE-004).

### 2.3 FIFO Queue Fairness

When multiple learner orders are eligible to match against the same external trade volume, they are filled in the order they entered the open-orders index (FIFO by `created_at`). This is for fairness across platform users; it does **not** reflect Binance's actual queue position (which is unknowable from public data).

### 2.4 Idempotency

Every consumed event has an `event_id`. Matching Engine keeps a deduplication table / cache (Redis set with TTL = 24h) of processed `event_id`s. Duplicate delivery from Kafka (at-least-once) is filtered out. This prevents double-fills on consumer replay.

---

## 3. Event Consumption & Production

### 3.1 Consumed Events

| Topic | Event | Partition Key | Consumer Group |
|-------|-------|---------------|----------------|
| `orders.events.v1` | `OrderPlaced` | `order_id` | `matching-engine` |
| `orders.events.v1` | `OrderCancelRequested` | `order_id` | `matching-engine` |
| `market-data.events.v1` | `ExternalTradeObserved` | `pair` | `matching-engine` |
| `market-data.events.v1` | `MarketDataFeedDegraded` | `pair` | `matching-engine` |
| `market-data.events.v1` | `MarketDataFeedRecovered` | `pair` | `matching-engine` |

### 3.2 Produced Events

All on topic `matching.events.v1`, partition key `order_id`.

| Event | When | Payload |
|-------|------|---------|
| `OrderPartiallyFilled` | After applying a partial fill to an order | `order_id, user_id, filled_qty, remaining_qty, last_fill_price, last_fill_qty, occurred_at` |
| `OrderFilled` | After applying a fill that brings `filled_qty == quantity` | `order_id, user_id, total_filled_qty, avg_fill_price, occurred_at` |
| `OrderCancelled` | After processing `OrderCancelRequested` | `order_id, user_id, filled_qty_at_cancel, remaining_qty, cancelled_at` |
| `OrderRejected` | Rare: depth exhausted for market order with zero fill | `order_id, user_id, reason, occurred_at` |
| `TradeExecuted` | Per individual fill (may emit multiple per `OrderPartiallyFilled`) | `trade_id, order_id, user_id, pair, side, price, quantity, quote_amount, fee_amount, fee_asset, role, executed_at` |

**Ordering guarantee:** For a single `order_id`, events are emitted in logical order: `TradeExecuted` (one or more) → `OrderPartiallyFilled` OR `OrderFilled`. Same partition key ensures Kafka preserves this order downstream.

### 3.3 Synchronous Calls (Outgoing)

- `Market Data Service — GET /internal/depth/{pair}` — used on market-order consumption to fetch a depth snapshot for walk-the-book. Cached per call; do not cache across market orders.
- `Market Data Service — GET /internal/ticker/{pair}` — fallback if depth is unavailable.

---

## 4. Functional Requirements (Detailed)

Inherits and expands SRS §3.4.

### SR-MATCH-ME-001 — Market Order Execution

**Sequence on `OrderPlaced` for a market order:**

1. Dedup check on `event_id`.
2. Fetch current depth snapshot for the pair from Market Data Service.
3. Apply walk-the-book:
   - For BUY: iterate `asks` from best (lowest) to worst; consume `min(remaining_qty, level_qty)` at each `level_price × (1 + slippage_rate)`.
   - For SELL: iterate `bids` from best (highest) to worst; consume `min(remaining_qty, level_qty)` at each `level_price × (1 - slippage_rate)`.
4. Each level consumed produces one `TradeExecuted` event (one fill at one price).
5. Continue until `remaining_qty == 0` or depth is exhausted.
6. Emit `OrderFilled` (if fully filled) or `OrderRejected` with reason `DEPTH_EXHAUSTED` (if zero fills and depth ran out) or `OrderPartiallyFilled` (if some but not all filled — rare for market).

**Acceptance Criteria:**

- **Given** a market BUY of 0.1 BTC and Binance asks are `[(60000, 0.05), (60010, 0.06), (60020, 0.10)]`, **when** processed, **then**:
  - First fill: 0.05 BTC @ `60000 × 1.0005 = 60030` → `TradeExecuted{price=60030, qty=0.05}`.
  - Second fill: 0.05 BTC @ `60010 × 1.0005 = 60040.005` → `TradeExecuted{price=60040.005, qty=0.05}`.
  - Then `OrderFilled{avg_fill_price=(60030×0.05 + 60040.005×0.05)/0.1 = 60035.0025, total_filled_qty=0.1}` is emitted.
- **Given** a market BUY of 10,000 BTC (far exceeds realistic depth), **when** processed, **then** fills walk through entire depth, `OrderPartiallyFilled` emitted with `filled_qty = total_depth_qty`, a separate follow-up `OrderCancelled` with `remaining_qty > 0` cancels the unfilled portion. (See SR-MATCH-ME-007 for the mechanism.)
- **Given** depth response is empty (degenerate), **when** processed, **then** `OrderRejected{reason: "DEPTH_EXHAUSTED"}` is emitted.

### SR-MATCH-ME-002 — Limit Order Registration & Matching

**Sequence on `OrderPlaced` for a limit order:**

1. Dedup check.
2. Insert into open-orders index keyed by `(pair, side, limit_price, created_at)`.
3. No immediate action; order waits for an `ExternalTradeObserved` to trigger.

**Sequence on `ExternalTradeObserved`:**

1. Dedup check.
2. For the incoming `pair`, scan open-orders index for orders whose `limit_price` satisfies the touch condition relative to `external_trade.price`:
   - BUY orders: `limit_price ≥ external_trade.price`.
   - SELL orders: `limit_price ≤ external_trade.price`.
3. Order candidates by FIFO (oldest `created_at` first), and by side symmetry — a single external trade is processed separately for BUY candidates and SELL candidates (they compete for different halves of the external volume: a Binance trade at price P with volume V is treated as `V` of buy liquidity from the external maker and `V` of sell liquidity from the external taker; platform BUYs consume sell-side liquidity, platform SELLs consume buy-side liquidity).
4. Iterate candidates in FIFO order:
   - `fill_qty = min(candidate.remaining_qty, external_trade_remaining_volume)`.
   - Emit `TradeExecuted` at `price = external_trade.price` (no slippage on limit orders).
   - Update candidate: `filled_qty += fill_qty`; if `filled_qty == quantity`, remove from index and emit `OrderFilled`; else emit `OrderPartiallyFilled`.
   - `external_trade_remaining_volume -= fill_qty`.
   - If `external_trade_remaining_volume == 0`, stop.

**Acceptance Criteria:**

- **Given** user A has OPEN BUY LIMIT 0.5 BTC @ 58000 and user B has OPEN BUY LIMIT 0.3 BTC @ 58000 (user A placed first), and an `ExternalTradeObserved` arrives with `price=57950, quantity=0.4`, **when** processed, **then**: both orders satisfy touch (58000 ≥ 57950). User A fills 0.4 BTC (all of external volume); user B receives nothing (external volume exhausted). Events: `TradeExecuted{order=A, qty=0.4, price=57950}`, `OrderPartiallyFilled{order=A, filled_qty=0.4, remaining_qty=0.1}`.
- **Given** user A has OPEN BUY LIMIT 0.1 BTC @ 58000, and `ExternalTradeObserved{price=58500, qty=1.0}` arrives, **when** processed, **then** no fill (58000 < 58500 fails touch for BUY).
- **Given** user A has OPEN SELL LIMIT 0.2 ETH @ 3000, and `ExternalTradeObserved{price=3001, qty=0.5}` arrives, **when** processed, **then** fill: 0.2 ETH @ 3001 → `TradeExecuted{price=3001, qty=0.2}`, `OrderFilled{avg_fill_price=3001, total_filled_qty=0.2}`.

### SR-MATCH-ME-003 — Fee Computation

**Requirement:** Every fill has a fee.

- Determine maker vs taker role:
  - Market orders are always Taker.
  - Limit orders that crossed the spread at placement (price ≥ external best ask for BUY, price ≤ external best bid for SELL) are Taker on first fill. In MVP, this distinction is not tracked per-order; since fee rates are equal (both 0.10%), **all fills are treated as Taker for fee computation**. The `role` field is populated for reporting but does not affect the fee.
- Compute fee:
  - BUY: `fee_amount = fill_quantity × taker_fee_rate`, `fee_asset = base_asset`. Example: BUY 0.1 BTC, fee = 0.0001 BTC.
  - SELL: `fee_amount = fill_quote_amount × taker_fee_rate` where `fill_quote_amount = fill_quantity × fill_price`, `fee_asset = quote_asset`. Example: SELL 0.1 BTC @ 60000, fee = 0.1 × 60000 × 0.001 = 6 USDT.

**Acceptance Criteria:**

- **Given** a BUY fill of 0.2 BTC @ 58000, **when** fee is computed, **then** `fee_amount = 0.0002 BTC, fee_asset = BTC`.
- **Given** a SELL fill of 0.5 ETH @ 3000, **when** fee is computed, **then** `fee_amount = 0.5 × 3000 × 0.001 = 1.5 USDT, fee_asset = USDT`.

### SR-MATCH-ME-004 — Cancellation Handling

**Sequence on `OrderCancelRequested`:**

1. Dedup check.
2. Look up order in open-orders index.
3. If found: remove from index, emit `OrderCancelled` with final `filled_qty` (from in-memory state) and `remaining_qty = quantity - filled_qty`.
4. If not found: either the order never entered the index (e.g., it was a market order that already executed fully) OR it was already removed (completed fill, prior cancel). Emit `OrderCancelled` only if the order is still cancellable from the MIE's perspective; else emit nothing (the `CANCEL_REQUESTED` will time out on the Order Service side — see SR-MATCH-EDGE-003).

**Acceptance Criteria:**

- **Given** an OPEN order with `filled_qty=0.3, quantity=1`, **when** `OrderCancelRequested` is consumed, **then** order removed from index, `OrderCancelled{filled_qty=0.3, remaining_qty=0.7}` emitted.
- **Given** an order that was never a limit (market order already filled), **when** `OrderCancelRequested` is consumed, **then** no `OrderCancelled` emitted; event is logged and dropped.

### SR-MATCH-ME-005 — Race Condition: Fill vs Cancel

**Scenario:** `OrderCancelRequested` and `ExternalTradeObserved` arrive nearly simultaneously for the same pair.

**Rule:** Process events in Kafka consumer order within each partition. `OrderCancelRequested` and `OrderPlaced` share partition (`order_id`). `ExternalTradeObserved` is in a different partition (`pair`). The engine's event loop must process them serially via a single-threaded executor per pair.

**Acceptance Criteria:**

- **Given** an order is in OPEN state with `remaining_qty=0.5`, and a cancel request is received at T=100ms while an external trade at that price arrives at T=99ms, **when** both events are in the pair's event queue, **then** the external trade is processed first (producing a partial fill), then the cancel (cancelling the remainder). Final state: `CANCELLED, filled_qty>0, remaining_qty<original_quantity`.
- **Given** the order is FULLY filled by the external trade, **when** the cancel is processed, **then** no-op on the index (already removed); log a WARN; no `OrderCancelled` event emitted. Order Service's `CANCEL_REQUESTED` state resolves via its reconciliation path (see SR-MATCH-EDGE-003).

### SR-MATCH-ME-006 — Feed Degradation

**Sequence on `MarketDataFeedDegraded`:**

1. Mark the pair as "paused" in a per-pair state map.
2. While paused:
   - New `OrderPlaced` events for the pair are still consumed and added to the index (for limit orders), but **no matching** occurs — the engine ignores `ExternalTradeObserved` for that pair (and there should be none, since the feed is degraded).
   - Market orders arriving while paused are rejected: emit `OrderRejected{reason: "MARKET_DATA_DEGRADED"}`.
3. On `MarketDataFeedRecovered`, resume normal matching.

**Acceptance Criteria:**

- **Given** pair BTC/USDT feed is degraded, **when** a market BUY on BTC/USDT arrives, **then** `OrderRejected` is emitted and no fill occurs.
- **Given** the same pair is degraded, **when** a limit BUY on BTC/USDT arrives, **then** it is added to the index normally (will match on feed recovery).

### SR-MATCH-ME-007 — Depth Exhaustion for Market Orders

**Scenario:** Market BUY of 100 BTC but total visible depth = 50 BTC.

**Rule:** Walk the book to exhaustion. Emit one `TradeExecuted` per level. After all fills:

- If `filled_qty == 0`: emit `OrderRejected{reason: "DEPTH_EXHAUSTED"}`. Wallet Service will consume and unfreeze. Order Service will transition state to `REJECTED`.
- If `0 < filled_qty < quantity`: emit `OrderPartiallyFilled`, then emit a synthetic `OrderCancelled{filled_qty, remaining_qty}` (the engine cancels its own order since market orders cannot rest on the book). Wallet Service unfreezes the remaining portion.

**Acceptance Criteria:**

- **Given** a market BUY of 100 BTC and total visible depth sums to 50 BTC across all levels, **when** processed, **then** fills total 50 BTC across multiple `TradeExecuted` events, `OrderPartiallyFilled{filled_qty=50, remaining_qty=50}` is emitted, followed by `OrderCancelled{filled_qty=50, remaining_qty=50}`.
- **Given** depth snapshot returns empty `[]`, **when** processed, **then** a single `OrderRejected{reason: "DEPTH_EXHAUSTED"}` is emitted.

### SR-MATCH-ME-008 — Idempotency

**Requirement:** Duplicate delivery of any event (from Kafka at-least-once semantics) must not produce duplicate fills, cancellations, or events.

**Implementation:**

- Redis set `mie:processed_events` with TTL 24h.
- Before processing an event: `SADD mie:processed_events <event_id>`. If it returned 0 (already present), skip.

**Acceptance Criteria:**

- **Given** `OrderPlaced{event_id=E1, order_id=O1, limit order}` is consumed at T1, **when** the same event is delivered again at T2 < T1 + 24h, **then** the order is NOT added to the index a second time, no event is emitted.
- **Given** `ExternalTradeObserved{event_id=E2}` is consumed and triggers fills, **when** E2 is redelivered, **then** no re-matching, no duplicate `TradeExecuted`.

### SR-MATCH-ME-009 — Trade Persistence

**Requirement:** Every `TradeExecuted` event corresponds to a durably persisted `Trade` row before the event is published.

**Implementation:** Same outbox pattern as Order Service. The engine writes the `Trade` to its DB and the outbox row in one transaction; the relay publishes to Kafka asynchronously.

**Acceptance Criteria:**

- **Given** a fill occurs, **when** the engine processes it, **then** a `Trade` row is persisted AND an outbox row is created in the same transaction. The `TradeExecuted` event is published when the outbox relay polls.

---

## 5. Data Model & Persistence

### 5.1 Table — trades

```sql
CREATE TABLE trades (
  id                UUID PRIMARY KEY,
  order_id          UUID NOT NULL,
  user_id           UUID NOT NULL,
  pair              VARCHAR(20) NOT NULL,
  side              VARCHAR(4) NOT NULL,
  price             NUMERIC(36, 18) NOT NULL,
  quantity          NUMERIC(36, 18) NOT NULL,
  quote_amount      NUMERIC(36, 18) NOT NULL,  -- price × quantity
  fee_amount        NUMERIC(36, 18) NOT NULL,
  fee_asset         VARCHAR(10) NOT NULL,
  role              VARCHAR(5) NOT NULL,        -- MAKER / TAKER
  external_trade_id VARCHAR(64) NULL,           -- Binance trade reference if applicable
  executed_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX ix_trades_user_executed ON trades (user_id, executed_at DESC);
CREATE INDEX ix_trades_order ON trades (order_id);
CREATE INDEX ix_trades_pair_executed ON trades (pair, executed_at DESC);

CREATE TABLE matching_outbox (
  id            UUID PRIMARY KEY,
  event_type    VARCHAR(40) NOT NULL,
  aggregate_id  UUID NOT NULL,
  payload_json  JSONB NOT NULL,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  published_at  TIMESTAMPTZ NULL,
  attempts      INT NOT NULL DEFAULT 0
);
CREATE INDEX ix_mie_outbox_unpublished ON matching_outbox (created_at) WHERE published_at IS NULL;
```

### 5.2 In-Memory State (Non-Persistent)

- Open-orders index (rebuilt on startup from Order Service).
- Per-pair feed status map (rebuilt on startup by polling Market Data health).
- Redis is used for idempotency dedup; matching logic itself is in-process.

---

## 6. Startup & Recovery

### 6.1 Cold Start Sequence

1. Boot Spring Boot, connect to DB, Kafka, Redis, Market Data Service.
2. Query Order Service `GET /internal/orders?state=OPEN,PARTIALLY_FILLED&limit=10000` with pagination.
3. For each order, insert into open-orders index.
4. Poll Market Data `/internal/market-data/health` → initialize per-pair feed status.
5. Start Kafka consumers with `auto.offset.reset = earliest` on first run, `latest` afterward (configurable).
6. Start outbox relay scheduler.
7. Service is ready — `/actuator/health` returns UP.

### 6.2 Recovery from Crash

- Kafka consumers resume from last committed offset (consumer group offset is server-side tracked).
- Idempotency dedup in Redis persists across restart (24h TTL).
- The outbox relay picks up unpublished rows.
- Open-orders index is rebuilt from Order Service state (idempotent — previous in-memory state is abandoned).

### 6.3 Scaling Considerations

MVP: single instance. If run in multiple instances:

- Open-orders index must be sharded per pair (partition key) — each instance owns a subset of pairs.
- Kafka consumer group naturally distributes partitions.
- Market data event partitioning by `pair` aligns.
- Order events partitioning by `order_id` means orders land on arbitrary instances — undesirable. Post-MVP, repartition orders by `pair` via a topology where a "router" service bridges `orders.events.v1` to `matching-orders.events.v1` keyed by pair. **Not MVP scope.**

---

## 7. Edge Cases & Error Handling

| ID | Scenario | Required Behavior |
|----|----------|-------------------|
| SR-MATCH-EDGE-001 | Market order arrives while pair feed is degraded. | `OrderRejected{reason: "MARKET_DATA_DEGRADED"}`. Wallet unfreezes via event consumption. |
| SR-MATCH-EDGE-002 | Depth snapshot contains a level with quantity = 0 (malformed data). | Skip that level; log a WARN; continue walking. |
| SR-MATCH-EDGE-003 | `OrderCancelRequested` arrives for an order that's no longer in the index (already filled). | Log WARN with reason `ORDER_NOT_IN_INDEX`; do NOT emit `OrderCancelled`. Order Service must reconcile state on its side: after T seconds (configurable, default 5s) in `CANCEL_REQUESTED` without receiving `OrderCancelled`, Order Service queries this service's `/internal/matching/orders/{id}` endpoint to verify; if "not found", transitions to the final state derived from actual fills. |
| SR-MATCH-EDGE-004 | `OrderPlaced` for a limit order arrives milliseconds after an `ExternalTradeObserved` that would have matched it. | The order is added to the index for future matches only. Past trades do NOT retroactively fill. This is the "miss" risk per BRD Risk R-012. Mitigation: the external trade buffer (§2.2) exists but is NOT used for retroactive fills in MVP — only for observability. |
| SR-MATCH-EDGE-005 | Two external trades arrive simultaneously on the same pair. | Processed serially in arrival order by the pair's single-threaded executor. Order preservation matters for FIFO fairness across competing open orders. |
| SR-MATCH-EDGE-006 | An external trade has zero quantity. | Skip; log a WARN; do not match anything. |
| SR-MATCH-EDGE-007 | An order's limit_price is negative or zero (corrupt event). | Log ERROR, skip, do not add to index. This should never happen (Order Service validates). |
| SR-MATCH-EDGE-008 | Kafka producer fails to publish a `TradeExecuted` event. | Outbox relay retries indefinitely with backoff. No duplicate fills (idempotency at downstream). If persistent failure > 1 hour: alert. |
| SR-MATCH-EDGE-009 | Startup fails to reach Order Service for open-orders sync. | Service starts in a degraded mode, refusing to process `ExternalTradeObserved` (incomplete state). Retries Order Service every 10s; once reachable, completes sync and enters normal mode. |
| SR-MATCH-EDGE-010 | Open-orders index grows beyond reasonable bounds (e.g., 10,000 orders per pair under load). | Log WARN; no hard limit in MVP. Order Service's per-user limit (SR-ORDER-AP-006) caps abuse. Post-MVP: hard limit per pair. |

---

## 8. Traceability

| Matching Engine Requirement | Parent SRS Requirement(s) | BRD Requirement(s) |
|----------------------------|--------------------------|--------------------|
| SR-MATCH-ME-001 | SR-MATCH-002, SR-MATCH-003, SR-MATCH-004 | BR-004 |
| SR-MATCH-ME-002 | SR-MATCH-005, SR-MATCH-006, SR-MATCH-007 | BR-005 |
| SR-MATCH-ME-003 | SR-MATCH-009, SR-MATCH-010 | BR-013 |
| SR-MATCH-ME-004 | BR-006 | BR-006 |
| SR-MATCH-ME-005 | SR-EDGE-004 | — |
| SR-MATCH-ME-006 | SR-MATCH-012, SR-EDGE-001 | NFR-013 |
| SR-MATCH-ME-007 | SR-MATCH-004 | — |
| SR-MATCH-ME-008 | SR-MATCH-011 | NFR-005 |
| SR-MATCH-ME-009 | SR-TRADE-001 | NFR-006 |

---

## 9. Implementation Notes for Coding Agent

- **Concurrency model:** Single-threaded executor per pair (e.g., `ConcurrentHashMap<Pair, ExecutorService>` of single-thread executors). All events for a pair queue on its executor; events across pairs run in parallel.
- **Precision:** Always use `BigDecimal` with explicit `MathContext` for division. Avoid `double`.
- **Dedup cache:** Redis `SET` with `SADD ... NX EX 86400`. Branch on return value.
- **Outbox relay:** Share implementation with Order Service if possible (library module in `exchange-common`? Or duplicate; MVP acceptable to duplicate).
- **In-memory index eviction:** On `OrderCancelled`, `OrderFilled`, or `OrderRejected`, remove from index. Test that every terminal path triggers removal.
- **Testing:** Simulation-heavy service — build a test harness that injects synthetic `ExternalTradeObserved` sequences and asserts on emitted events. Include a property-based test: invariant "sum of all TradeExecuted quantities per order ≤ order quantity".

---

*End of `SRS_Appendix_MatchingEngine.md`.*
