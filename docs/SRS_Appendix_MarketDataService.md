# SRS Appendix — Market Data Service

**Parent Document:** `SRS.md` v1.0
**Service:** Market Data Service
**Status:** REST-based scaffold complete (per userMemories); WebSocket and depth work pending per SRS decisions
**Owned Entities:** `Candlestick` (TimescaleDB), cached depth and trade data (Redis)

---

## 1. Purpose & Boundaries

The Market Data Service is the single source of market information for the platform. It ingests data from Binance (REST + WebSocket), stores time-series OHLCV data, caches current order book depth and recent trades, and exposes the combined data to:

- **Frontend** via TradingView UDF-compatible endpoints (for charts).
- **Matching Engine** via internal endpoints (depth snapshots, trade stream).
- **Order Service** via internal endpoints (ticker, pair metadata).
- **All services** via Kafka events (`ExternalTradeObserved`, feed health events).

This service's scope has been expanded during SRS from the original REST-only design (userMemories) to include WebSocket trade and depth streams. This is driven by the partial-fill and walk-the-book simulation decisions (§Nhóm 2 of interview).

### 1.1 Service Responsibilities

- Ingest Binance REST kline data for all supported pairs and intervals; persist to TimescaleDB.
- Subscribe to Binance WebSocket `@trade` and `@depth` streams for all supported pairs.
- Cache current best bid/ask and current depth snapshot in Redis with short TTL.
- Publish `ExternalTradeObserved` Kafka events (one per external trade per supported pair).
- Fetch Binance `exchangeInfo` on startup and every 24 hours; cache pair metadata (tick size, step size) in Redis.
- Expose TradingView UDF endpoints (`/udf/config`, `/udf/symbols`, `/udf/history`).
- Expose internal REST endpoints for other services (ticker, depth, pair metadata, health).
- Detect and report feed degradation via Kafka (`MarketDataFeedDegraded`, `MarketDataFeedRecovered`).
- Implement provider abstraction to support post-MVP VN equity feeds.

### 1.2 Service Non-Responsibilities

- Matching or simulation (Matching Engine).
- Order state (Order Service).
- User data or authentication (User/Auth Service).

### 1.3 Bounded Context

"External market observation and distribution." Internal market state (platform's own order book — not applicable in MVP) would be a different context.

---

## 2. Data Sources & Streams

### 2.1 Binance REST Endpoints (Inbound)

| Endpoint | Purpose | Call Frequency |
|----------|---------|----------------|
| `GET /api/v3/exchangeInfo` | Symbol metadata (tick size, step size, filters) | Once on startup + every 24h |
| `GET /api/v3/klines` | Historical OHLCV bars | On-demand for backfill; periodic (1m interval) for gap detection |
| `GET /api/v3/depth` | Order book snapshot | On-demand (Matching Engine walk-the-book); every 60s for cache refresh |
| `GET /api/v3/ticker/bookTicker` | Best bid/ask only | Every 1s as fallback to WS ticker |

### 2.2 Binance WebSocket Streams (Inbound)

Combined stream URL: `wss://stream.binance.com:9443/stream?streams=<stream1>/<stream2>/...`

| Stream | Format | Purpose |
|--------|--------|---------|
| `<symbol>@trade` | `{e:"trade", E:timestamp, s:symbol, t:tradeId, p:price, q:qty, T:tradeTime, m:buyerIsMaker}` | Feeds `ExternalTradeObserved` events. One per Binance trade. |
| `<symbol>@depth20@100ms` | `{e:"depthUpdate", lastUpdateId, bids:[[price,qty]...], asks:[[price,qty]...]}` | Maintains cached depth snapshot (top 20 levels per side, updated every 100ms). |
| `<symbol>@kline_1m` | `{e:"kline", k:{t,T,s,i,o,c,h,l,v,x,...}}` | Optional live kline updates; primary source remains REST /klines with 1m polling to keep MVP simple. |

**Stream connections:**

- Single WebSocket connection multiplexing all required streams for all 5 pairs (10 streams: 5 @trade + 5 @depth).
- Optional 6th type `@kline_1m` per pair (another 5) if live chart updates are desired in MVP; else polling `GET /klines` every 30s is sufficient.
- Binance limit: max 1024 streams per connection and max 5 messages/s from client → comfortably within MVP.

### 2.3 Data Outputs

**To Frontend (HTTPS REST):**
- `/udf/config` — TradingView UDF config.
- `/udf/symbols?symbol=BTCUSDT` — symbol info.
- `/udf/history?symbol=BTCUSDT&resolution=1&from=...&to=...` — OHLCV bars.

**To Internal Services (HTTPS REST):**
- `GET /internal/ticker/{pair}` — current best bid/ask.
- `GET /internal/depth/{pair}` — current cached depth.
- `GET /internal/pairs/{pair}/metadata` — tick size, step size, min notional.
- `GET /internal/market-data/health` — per-pair feed status.

**To All (Kafka):**
- Topic `market-data.events.v1`:
  - `ExternalTradeObserved` — one per Binance trade on supported pairs.
  - `MarketDataFeedDegraded` — when feed stalls or disconnects.
  - `MarketDataFeedRecovered` — when feed resumes.
  - `PairMetadataUpdated` — when `exchangeInfo` changes.

**To Frontend (WebSocket, via API/WebSocket Gateway):**
- Current depth (top 20 levels per side) — pushed at ≥ 2 Hz.
- Latest trades ticker — pushed as they arrive.
- Live kline updates — pushed as they tick.

(Note: WebSocket Gateway is a separate service; Market Data Service pushes events to the gateway via Kafka, and the gateway fans out to connected clients.)

---

## 3. Functional Requirements (Detailed)

Inherits and expands SRS §3.6.

### SR-MD-MD-001 — Ingest OHLCV on Boot

**Requirement:** On startup, ensure TimescaleDB has at least the last 30 days of 1m bars, the last 90 days of 1h bars, the last 365 days of 4h and 1d bars for all supported pairs.

**Implementation:**
- Startup job queries the max `open_time` in `candlesticks` per (pair, interval).
- For each gap, fetches `GET /api/v3/klines` in batches of 1000 bars.
- Respects Binance REST rate limits (1200 requests/min IP limit).

**Acceptance Criteria:**
- **Given** the service starts with an empty `candlesticks` table, **when** startup completes, **then** all pairs × intervals have the specified history in TimescaleDB and `/udf/history` returns data for a matching range.
- **Given** the service starts with 2 days of 1m data already present, **when** startup completes, **then** only the gap (up to current time) is fetched and inserted.

### SR-MD-MD-002 — Subscribe to Binance WebSocket

**Requirement:** Maintain a persistent WebSocket connection to Binance combined stream for all 5 pairs' `@trade` and `@depth20@100ms` streams.

**Implementation:** Spring WebFlux `WebSocketClient` (reactive) or OkHttp WebSocket with Spring wrapper. Single connection; reconnect with exponential backoff on disconnect.

**Acceptance Criteria:**
- **Given** the service is running, **when** it is queried for connection status, **then** the WS connection is open (if Binance is reachable).
- **Given** the WS connection drops, **when** reconnection is attempted, **then** backoff is applied (1s, 2s, 4s, 8s, 16s, cap 60s), and `MarketDataFeedDegraded` is emitted on every pair after 30s of disconnection.
- **Given** reconnection succeeds after 45s, **when** it completes, **then** `MarketDataFeedRecovered` events are emitted for all affected pairs.

### SR-MD-MD-003 — Process Trade Stream

**Requirement:** Every Binance `@trade` event is converted to an `ExternalTradeObserved` Kafka event.

**Mapping:**
```
Binance @trade → ExternalTradeObserved:
  s (symbol)          → pair
  p (price)           → price
  q (quantity)        → quantity
  m (buyer_is_maker)  → buyer_is_maker
  T (trade_time ms)   → event_time (ISO 8601)
  t (trade_id)        → external_trade_id (optional, for dedup)
```

**Acceptance Criteria:**
- **Given** Binance WS delivers `{e:"trade", s:"BTCUSDT", p:"60050.25", q:"0.5", m:true, T:1713600000000}`, **when** processed, **then** an `ExternalTradeObserved{pair:"BTCUSDT", price:60050.25, quantity:0.5, buyer_is_maker:true, event_time:"2026-04-20T10:40:00Z"}` is published on Kafka.
- **Given** 100 trade events arrive in 1 second for BTC/USDT, **when** processed, **then** 100 Kafka events are published in the same order. No drops.
- **Given** a trade event has an invalid price (`-1`, `0`, or non-numeric), **when** received, **then** it is logged and dropped; no Kafka event.

### SR-MD-MD-004 — Maintain Depth Cache

**Requirement:** For each supported pair, maintain a Redis-cached current depth snapshot (top 20 bid levels, top 20 ask levels) updated from WS depth events.

**Implementation:**
- Redis key: `md:depth:{pair}` → JSON `{bids: [[price, qty]...], asks: [[price, qty]...], updated_at}`.
- TTL: 5 seconds (safety net in case updates stop without disconnect detection).
- On every `@depth20@100ms` event, atomically update the full top-20 payload.

**Acceptance Criteria:**
- **Given** a `@depth20@100ms` event arrives with 20 bids and 20 asks, **when** processed, **then** the Redis key is updated with the full snapshot and `updated_at` is current time.
- **Given** no depth events arrive for 6 seconds (e.g., WS stalled but not disconnected), **when** `GET /internal/depth/{pair}` is called, **then** Redis returns null (key expired), and the service falls back to `GET /api/v3/depth` REST call.
- **Given** the fallback REST call also fails, **when** the endpoint returns, **then** response is `503 Service Unavailable` with body `{error: "DEPTH_UNAVAILABLE"}`.

### SR-MD-MD-005 — Maintain Ticker Cache

**Requirement:** Current best bid/ask per pair, updated from depth events (level 0) or WS bookTicker stream if available.

**Implementation:** Derive best bid = `bids[0]` and best ask = `asks[0]` from the depth snapshot. Cache separately in Redis `md:ticker:{pair}` for fast access.

**Acceptance Criteria:**
- **Given** a depth update, **when** processed, **then** `md:ticker:{pair}` reflects the new top bid/ask within 100ms of the depth update.
- **Given** `GET /internal/ticker/{pair}` is called, **when** serviced, **then** response includes `{pair, best_bid, best_ask, updated_at}`.

### SR-MD-MD-006 — Fetch and Cache ExchangeInfo

**Requirement:** Fetch `GET /api/v3/exchangeInfo` on startup and every 24 hours; extract tick size, step size, filter data for supported pairs; cache in Redis.

**Implementation:**
- Redis hash: `md:exchangeInfo` → field per pair with JSON value.
- On update, emit `PairMetadataUpdated` event on Kafka for any changed fields.

**Acceptance Criteria:**
- **Given** service starts, **when** startup completes, **then** `HGET md:exchangeInfo BTCUSDT` returns JSON including `{tick_size, step_size, base_asset, quote_asset, status}`.
- **Given** Binance changes BTC/USDT tick size in `exchangeInfo`, **when** the 24h refresh runs, **then** the Redis hash is updated and `PairMetadataUpdated{pair: "BTCUSDT", old_value, new_value}` is emitted.

### SR-MD-MD-007 — Expose TradingView UDF Endpoints

**Requirement:** Implement the TradingView Universal Data Feed protocol subset required by Lightweight Charts.

**Endpoints:**

```
GET /udf/config
Response:
{
  "supports_search": true,
  "supports_group_request": false,
  "supported_resolutions": ["1", "5", "15", "60", "240", "1D"],
  "supports_marks": false,
  "supports_timescale_marks": false
}

GET /udf/symbols?symbol=BTCUSDT
Response:
{
  "symbol": "BTCUSDT",
  "name": "BTC/USDT",
  "description": "Bitcoin / Tether",
  "type": "crypto",
  "session": "24x7",
  "timezone": "Etc/UTC",
  "exchange": "Binance (sim)",
  "minmov": 1,
  "pricescale": 100,
  "has_intraday": true,
  "supported_resolutions": ["1", "5", "15", "60", "240", "1D"]
}

GET /udf/history?symbol=BTCUSDT&resolution=1&from=1713600000&to=1713700000
Response (UDF compact format):
{
  "s": "ok",
  "t": [1713600000, 1713600060, ...],   // open times (seconds)
  "o": [60000.0, 60010.5, ...],          // opens
  "h": [60050.0, 60025.0, ...],          // highs
  "l": [59980.0, 60005.0, ...],          // lows
  "c": [60010.5, 60020.0, ...],          // closes
  "v": [12.5, 8.3, ...]                  // volumes
}

No-data response:
{ "s": "no_data", "nextTime": 1713599000 }
```

**Acceptance Criteria:**
- **Given** a TradingView Lightweight Chart is configured with this service's UDF base URL, **when** the chart loads, **then** it renders BTC/USDT candlesticks for the requested range.
- **Given** the requested range is beyond available data, **when** the endpoint returns, **then** `{"s": "no_data"}` is sent (not an error).

### SR-MD-MD-008 — Expose Health Endpoint

**Requirement:** `/internal/market-data/health` returns per-pair feed health.

```
GET /internal/market-data/health
Response:
{
  "pairs": {
    "BTCUSDT": {
      "trade_last_update": "2026-04-20T10:45:12Z",
      "depth_last_update": "2026-04-20T10:45:11.850Z",
      "trade_rate_per_sec": 3.2,
      "status": "HEALTHY"   // HEALTHY | STALE | DEGRADED | DISCONNECTED
    },
    ...
  },
  "binance_ws_connected": true,
  "overall_status": "HEALTHY"
}
```

**Status semantics:**
- `HEALTHY`: last update < 2s ago.
- `STALE`: 2–10s since last update.
- `DEGRADED`: > 10s since last update.
- `DISCONNECTED`: WS connection is down.

**Acceptance Criteria:**
- **Given** all pairs are receiving WS updates regularly, **when** health is queried, **then** every pair is `HEALTHY` and `overall_status=HEALTHY`.
- **Given** BTC/USDT has not had a trade update for 15s but WS is connected, **when** queried, **then** BTC/USDT is `DEGRADED`.

### SR-MD-MD-009 — Feed Degradation Event Emission

**Requirement:** Transitions between health statuses trigger Kafka events.

**Transition rules:**
- `HEALTHY → DEGRADED` or `HEALTHY → DISCONNECTED` → emit `MarketDataFeedDegraded{pair, reason, degraded_since}`.
- `DEGRADED → HEALTHY` or `DISCONNECTED → HEALTHY` → emit `MarketDataFeedRecovered{pair, recovered_at}`.
- `STALE` does not trigger events (transient).

**Acceptance Criteria:**
- **Given** BTC/USDT trade stream stalls, **when** 10s pass without an update, **then** `MarketDataFeedDegraded{pair:"BTCUSDT", reason:"STALE_TRADE_STREAM"}` is emitted.
- **Given** updates resume, **when** a fresh trade arrives, **then** `MarketDataFeedRecovered{pair:"BTCUSDT"}` is emitted.

### SR-MD-MD-010 — Sanity Checks on Incoming Ticks

**Requirement:** Price sanity filter per SR-MD-011.

**Rule:** Maintain `last_valid_price` per pair. A new tick with `|new_price - last_valid_price| / last_valid_price > 0.10` is treated as suspicious. In MVP:
- Log WARN.
- Still emit the event (do not drop) — we trust Binance. Dropping would break arbitrage-protection logic downstream.
- Track count of suspicious ticks per pair per minute; alert if > 5/min.

Post-MVP: consider dropping if confirmed anomalies are recurrent.

**Acceptance Criteria:**
- **Given** last valid BTC price is 60000 and a tick arrives at 75000 (25% jump), **when** processed, **then** a WARN is logged with fields `{pair, last_valid_price, new_price}`, but the `ExternalTradeObserved` is still emitted.

### SR-MD-MD-011 — Provider Abstraction

**Requirement:** All interactions with Binance-specific APIs go through a `MarketDataProvider` interface.

**Interface (Java):**

```java
public interface MarketDataProvider {
    String getName();                                        // e.g., "binance"
    List<String> getSupportedPairs();
    PairMetadata getPairMetadata(String pair);
    CompletableFuture<List<Kline>> fetchKlines(String pair, Interval interval, Instant from, Instant to);
    DepthSnapshot getCurrentDepth(String pair);             // from cache or fallback REST
    Ticker getCurrentTicker(String pair);
    Flux<ExternalTrade> subscribeTradeStream();             // reactive stream
    Flux<DepthUpdate> subscribeDepthStream();
}
```

MVP implementation: `BinanceMarketDataProvider`. Post-MVP: `SsiMarketDataProvider`, `TcbsMarketDataProvider` for Vietnamese equities.

**Acceptance Criteria:**
- **Given** the provider is `binance`, **when** the service boots, **then** all data flows through `BinanceMarketDataProvider`; no Binance-specific code lives outside this class (save for test data).
- **Given** a future provider is plugged in via Spring `@ConditionalOnProperty`, **when** `market.data.provider=ssi`, **then** no Binance HTTP calls are made; all market data comes from `SsiMarketDataProvider`.

---

## 4. Data Model

### 4.1 TimescaleDB — Candlesticks

```sql
CREATE TABLE candlesticks (
  pair_symbol VARCHAR(20)   NOT NULL,
  interval   VARCHAR(5)    NOT NULL,    -- "1m", "5m", "15m", "1h", "4h", "1d"
  open_time  TIMESTAMPTZ   NOT NULL,
  open       NUMERIC(36, 18) NOT NULL,
  high       NUMERIC(36, 18) NOT NULL,
  low        NUMERIC(36, 18) NOT NULL,
  close      NUMERIC(36, 18) NOT NULL,
  volume     NUMERIC(36, 18) NOT NULL,
  quote_volume NUMERIC(36, 18) NOT NULL,
  trade_count INTEGER NOT NULL,
  PRIMARY KEY (pair_symbol, interval, open_time)
);

-- Convert to TimescaleDB hypertable, partitioned by open_time
SELECT create_hypertable('candlesticks', 'open_time', chunk_time_interval => INTERVAL '7 days');

CREATE INDEX ix_cs_pair_interval_time ON candlesticks (pair_symbol, interval, open_time DESC);

-- Note: full DDL (with close_time, ingested_at, upsert semantics) is in
-- SystemDesign_Appendix_MarketDataService.md §5.1 — that is the authoritative version.
```

### 4.2 Redis Keys

| Key pattern | Type | TTL | Content |
|-------------|------|-----|---------|
| `md:depth:{pair}` | String (JSON) | 5s | Current depth snapshot |
| `md:ticker:{pair}` | String (JSON) | 5s | Current best bid/ask |
| `md:exchangeInfo` | Hash | 24h | Per-pair metadata |
| `md:health:{pair}` | Hash | none | `trade_last_update`, `depth_last_update`, `status` |

---

## 5. Operational Concerns

### 5.1 Rate Limiting (Outbound)

Binance REST: 1200 requests/min per IP. This service conservatively targets ≤ 600/min by:
- Single startup backfill burst (completes in ~1 minute for initial history).
- Steady state: `exchangeInfo` once per day + periodic depth fallback calls (≤ 10/min worst case).

Binance WebSocket: no rate limit concern in inbound direction; outbound pings every 30s per protocol.

### 5.2 Reconnection Strategy

- Exponential backoff: 1s, 2s, 4s, 8s, 16s, max 60s.
- After reconnect: immediately re-subscribe to all streams.
- After reconnect: query `/api/v3/depth` for a fresh snapshot to resolve any diff gap (if diff-based depth stream is used; not applicable with `@depth20@100ms` which sends full snapshots).

### 5.3 Kafka Producer Configuration

- `acks=all` for `ExternalTradeObserved` (ensures durability; slight latency cost acceptable).
- `compression=zstd` for payload size reduction.
- Partition by `pair` → same pair's events land on the same partition → preserves per-pair order.

### 5.4 Observability

- Metrics: `md.trade_events.count` (by pair), `md.depth_events.count`, `md.ws.reconnect_count`, `md.health_status` (gauge per pair), `md.binance.rest.calls.count`.
- Dashboards (post-MVP): per-pair event rate, depth freshness histogram, health timeline.

---

## 6. Edge Cases & Error Handling

| ID | Scenario | Required Behavior |
|----|----------|-------------------|
| SR-MD-EDGE-001 | Binance returns HTTP 429 (rate limit) on REST. | Back off per `Retry-After` header; retry up to 3 times. |
| SR-MD-EDGE-002 | Binance returns HTTP 418 (IP banned, temporary). | Log ERROR, circuit-break all REST calls for 5 minutes. Emit `MarketDataFeedDegraded` for all pairs (REST-dependent operations blocked). |
| SR-MD-EDGE-003 | Kafka producer is down. | Buffer events in memory (bounded queue of 10000); drop oldest on overflow with WARN log. When Kafka recovers, drain the queue. Accept small data loss for MVP. |
| SR-MD-EDGE-004 | TimescaleDB is down during backfill. | Retry with backoff; fail startup after 5 minutes. |
| SR-MD-EDGE-005 | WebSocket delivers a message for a non-supported pair (misconfiguration). | Log WARN, drop the message. |
| SR-MD-EDGE-006 | UDF `/udf/history` is called for a pair not in the system. | Return `{"s":"error","errmsg":"unknown_symbol"}` with HTTP 200 (per UDF protocol). |
| SR-MD-EDGE-007 | UDF `/udf/history` for a range exceeding 1,000 bars (abuse prevention). | Reject with `400 RANGE_TOO_LARGE`. Clients must page within the 1,000-bar cap (TradingView does this via `countback`). |
| SR-MD-EDGE-008 | Depth snapshot from Binance arrives with bids unsorted. | Sort bids descending, asks ascending, before caching. Log WARN if sort was needed. |

---

## 7. Traceability

| MD Service Requirement | Parent SRS Requirement(s) | BRD Requirement(s) |
|----------------------|--------------------------|--------------------|
| SR-MD-MD-001 | SR-MD-001, SR-MD-002 | BR-011 |
| SR-MD-MD-002 | SR-MD-003 | BR-014 |
| SR-MD-MD-003 | SR-MD-005 | BR-014 |
| SR-MD-MD-004 | SR-MD-004, SR-MD-008 | BR-012 |
| SR-MD-MD-005 | SR-MD-009 | BR-014 |
| SR-MD-MD-006 | SR-MD-006 | BR-014 |
| SR-MD-MD-007 | SR-MD-007 | BR-011 |
| SR-MD-MD-008 | SR-MD-010 | NFR-013 |
| SR-MD-MD-009 | SR-MD-010 | NFR-013 |
| SR-MD-MD-010 | SR-MD-011 | BRD Risk R-009 |
| SR-MD-MD-011 | SR-MD-012 | BR-018, BRD Risk R-003 |

---

## 8. Implementation Notes for Coding Agent

- **Reactive stack:** Spring WebFlux is a natural fit for WS ingestion. Use `reactor-netty` for the Binance WS client.
- **Shared WS connection:** One `WebSocketClient` managing one connection with the combined stream URL. Use `RSocket`-style multiplexing conceptually; Binance protocol handles stream routing via `stream` field in the JSON.
- **Ingestion → Kafka pipeline:** Use Reactor's `Flux` pipeline: `wsMessages → parse → filter supported pairs → transform → publish to Kafka` with backpressure. Use `Sinks.Many` if bridging to imperative Kafka producer.
- **TimescaleDB:** Standard JDBC with HikariCP. Use `ON CONFLICT (pair, interval, open_time) DO UPDATE` for idempotent backfill.
- **UDF endpoints:** Keep the UDF JSON format exact — TradingView is picky. Test with real Lightweight Charts widget early.
- **Testing:** Mock Binance WS using an in-process WS server (e.g., Ratpack or nanoHTTPD). Replay fixture files (recorded real Binance traces) in unit tests.

---

*End of `SRS_Appendix_MarketDataService.md`.*
