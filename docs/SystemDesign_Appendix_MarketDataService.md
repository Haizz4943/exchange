# System Design Appendix — Market Data Service

**Parent Document:** `SystemDesign.md` v1.0
**Service:** `market-data-service`
**Port:** 8085
**Owned Bounded Context:** External market observation & distribution
**Owned Entities:** `Candlestick` (TimescaleDB), cached depth/ticker (Redis)
**Related SRS:** `SRS_Appendix_MarketDataService.md` v1.0
**Status:** Scaffold already exists (per user memory — REST-based); this appendix specifies the WS + Kafka expansion and the provider abstraction

---

## Table of Contents

1. [Scope & Design Goals](#1-scope--design-goals)
2. [Module Structure](#2-module-structure)
3. [Provider Abstraction](#3-provider-abstraction)
4. [Binance Integration Design](#4-binance-integration-design)
5. [Data Model](#5-data-model)
6. [REST API Design](#6-rest-api-design)
7. [Kafka Integration](#7-kafka-integration)
8. [Ingestion Pipeline (Reactive)](#8-ingestion-pipeline-reactive)
9. [Startup & Backfill](#9-startup--backfill)
10. [Feed Health Monitoring](#10-feed-health-monitoring)
11. [Configuration](#11-configuration)
12. [Error Handling & Edge Cases](#12-error-handling--edge-cases)
13. [Testing Strategy](#13-testing-strategy)
14. [Open Implementation Notes](#14-open-implementation-notes)

---

## 1. Scope & Design Goals

This appendix specifies the **implementation-level design** for Market Data Service. It assumes familiarity with `SystemDesign.md` (particularly §5 Communication Patterns, §6.4 TimescaleDB Design) and `SRS_Appendix_MarketDataService.md`.

### 1.1 Design Goals (in priority order)

1. **Deliver Binance data faithfully and promptly.** Trade events ≤ 500 ms from observation to Kafka publish (p95); kline updates ≤ 2 s to chart (p95).
2. **Survive WebSocket outages gracefully.** Reconnect with backoff; emit feed status events so downstream services can adapt; never corrupt state.
3. **Be the only service that talks to Binance.** All Binance-specific code isolated behind a provider interface — other services see only abstract market data concepts.
4. **Stateless where possible.** Candlesticks persist (TimescaleDB); ticker/depth are Redis cache (regenerable from Binance). No custom transactional DB — no outbox for Kafka events (except for durable ones, see §7).
5. **Pluggable provider.** SSI/TCBS providers post-MVP require zero changes outside a new `XxxMarketDataProvider` class.
6. **TradingView UDF protocol compliance.** Lightweight Charts is picky — the UDF JSON must match spec byte-for-byte.

### 1.2 What's Explicitly Out of Scope

- **Matching / execution** — Matching Engine.
- **Historical trade persistence** — trades are published to Kafka but not persisted in Market Data's DB. If trade history is needed for analytics, a downstream service consumes from Kafka.
- **User-specific data** — this service is fully multi-tenant and user-agnostic.

### 1.3 Key Architectural Distinctions from Other Services

- **Reactive stack (WebFlux, not MVC).** Ingestion of high-volume WS streams demands non-blocking I/O. Internal endpoints also reactive for consistency.
- **No transactional PostgreSQL for its own domain.** TimescaleDB is the only DB (separate instance). No outbox for `ExternalTradeObserved` / depth / kline events — these are "ephemeral observations"; loss of one is acceptable. Only durable events use Kafka with retries.
- **External dependency is HOT.** Service is useless without Binance WS — degraded-mode is a first-class state, not an afterthought.

---

## 2. Module Structure

### 2.1 Maven Module Location

```
haizz-exchange/
├── exchange-common/
├── market-data-service/                ← this module
├── order-service/
├── matching-engine/
├── ...
```

`market-data-service/pom.xml` declares:
- Dependencies: `exchange-common`, `spring-boot-starter-webflux`, `spring-boot-starter-data-redis-reactive`, `spring-kafka`, `spring-boot-starter-actuator`, `postgresql` driver, `jdbc-hikari`, `flyway-core`, `resilience4j-spring-boot3`, `reactor-netty`, `micrometer-registry-prometheus`, `mapstruct`, `lombok`.
- **Not** `spring-boot-starter-web` (WebFlux replaces it) — avoid conflict.
- **Not** `spring-boot-starter-data-jpa` — Candlestick writes use plain JDBC with batch insert for performance; TimescaleDB-specific behavior (hypertables, ON CONFLICT) doesn't benefit from JPA.
- Test: `spring-boot-starter-test` (reactor-test), `testcontainers` (postgres-timescaledb variant, kafka, redis).

### 2.2 Package Layout

```
com.haizz.exchange.marketdata/
├── MarketDataServiceApplication.java
│
├── api/                                # REST / UDF endpoints (WebFlux)
│   ├── UdfController.java              # /udf/config, /udf/symbols, /udf/history
│   ├── PublicController.java           # /api/v1/marketdata/* — user-facing via Gateway
│   ├── InternalController.java         # /internal/* — other services
│   ├── dto/
│   │   ├── UdfHistoryResponse.java     # compact UDF shape {s,t,o,h,l,c,v}
│   │   ├── UdfSymbolInfoResponse.java
│   │   ├── TickerResponse.java
│   │   ├── DepthResponse.java
│   │   ├── PairMetadataResponse.java
│   │   └── HealthResponse.java
│   └── GlobalExceptionHandler.java
│
├── application/
│   ├── ingestion/
│   │   ├── TradeIngestionService.java          # WS @trade → ExternalTradeObserved
│   │   ├── DepthIngestionService.java          # WS @depth → Redis + Kafka
│   │   └── KlineIngestionService.java          # optional WS @kline → Redis + Kafka
│   ├── backfill/
│   │   ├── CandlestickBackfillService.java     # startup + gap-filling
│   │   └── BackfillPlan.java
│   ├── exchangeinfo/
│   │   └── ExchangeInfoSyncService.java        # startup + 24h refresh
│   ├── health/
│   │   └── FeedHealthMonitor.java              # periodic check, emits Degraded/Recovered
│   └── query/
│       ├── GetHistoryUseCase.java
│       ├── GetDepthUseCase.java
│       ├── GetTickerUseCase.java
│       └── GetPairMetadataUseCase.java
│
├── domain/                             # pure domain — stays minimal here
│   ├── Candlestick.java                # value object
│   ├── Ticker.java                     # value object
│   ├── DepthSnapshot.java              # value object
│   ├── PairMetadata.java               # value object
│   ├── TradeObservation.java           # value object (what ingestion publishes)
│   ├── PairHealth.java                 # value object (status + last-seen timestamps)
│   ├── FeedStatus.java                 # enum: HEALTHY | STALE | DEGRADED | DISCONNECTED
│   └── exception/
│       ├── DepthUnavailableException.java
│       ├── PairNotSupportedException.java
│       └── RangeTooLargeException.java
│
├── infrastructure/
│   ├── provider/
│   │   ├── MarketDataProvider.java             # THE interface
│   │   ├── binance/
│   │   │   ├── BinanceMarketDataProvider.java  # @ConditionalOnProperty("market.data.provider=binance")
│   │   │   ├── BinanceRestClient.java          # klines, exchangeInfo, depth REST
│   │   │   ├── BinanceWebSocketClient.java     # combined stream, reconnect, backoff
│   │   │   ├── mapper/
│   │   │   │   ├── BinanceTradeMapper.java
│   │   │   │   ├── BinanceDepthMapper.java
│   │   │   │   ├── BinanceKlineMapper.java
│   │   │   │   └── BinanceExchangeInfoMapper.java
│   │   │   └── dto/                            # Binance's JSON shapes (internal only)
│   │   │       ├── BinanceTradeEvent.java
│   │   │       ├── BinanceDepthEvent.java
│   │   │       ├── BinanceKlineEvent.java
│   │   │       ├── BinanceKlineResponse.java
│   │   │       └── BinanceExchangeInfo.java
│   │   └── (post-MVP: ssi/, tcbs/)
│   ├── persistence/
│   │   ├── CandlestickJdbcRepository.java      # plain JDBC batch insert
│   │   └── migrations (handled by Flyway)
│   ├── cache/
│   │   ├── TickerRedisRepository.java          # reactive Redis ops
│   │   ├── DepthRedisRepository.java
│   │   ├── ExchangeInfoRedisRepository.java    # hash-based
│   │   └── HealthRedisRepository.java          # last-seen timestamps
│   ├── messaging/
│   │   └── producer/
│   │       ├── MarketDataEventPublisher.java   # direct Kafka (no outbox for ephemeral events)
│   │       └── FeedStatusEventPublisher.java   # durable — uses light outbox pattern (§7)
│   └── http/                                   # clients for internal services
│       └── (none — MarketDataService calls no other internal services)
│
├── config/
│   ├── WebFluxConfig.java
│   ├── JdbcConfig.java
│   ├── RedisConfig.java                # reactive + sync (hybrid)
│   ├── KafkaConfig.java
│   ├── WebSocketClientConfig.java
│   ├── ProviderConfig.java             # wires MarketDataProvider via @ConditionalOnProperty
│   └── StartupRunner.java              # backfill + exchangeInfo sync + subscribe WS
│
└── shared/
    ├── Constants.java
    └── SupportedPairs.java             # Set<PairSymbol> — configurable
```

### 2.3 Dependency Direction

Hexagonal as before. The **`application.ingestion`** layer depends on **`infrastructure.provider.MarketDataProvider`** (the interface), NOT on `BinanceMarketDataProvider`. All Binance-specific imports are confined to `infrastructure.provider.binance.*`.

ArchUnit rule enforcing this:

```java
@ArchTest
static final ArchRule no_binance_outside_provider_package =
    noClasses().that().resideOutsideOfPackage("..infrastructure.provider.binance..")
        .should().dependOnClassesThat().haveNameMatching(".*Binance.*");
```

---

## 3. Provider Abstraction

The single most important design element for post-MVP extensibility.

### 3.1 The Interface

```java
// infrastructure/provider/MarketDataProvider.java
public interface MarketDataProvider {

    /** Subscribe to live trade events. Returns a reactive stream. */
    Flux<TradeObservation> streamTrades(Set<PairSymbol> pairs);

    /** Subscribe to live depth snapshots. */
    Flux<DepthSnapshot> streamDepth(Set<PairSymbol> pairs);

    /** Subscribe to live kline updates (optional — MVP may fall back to periodic REST polling). */
    Flux<KlineUpdate> streamKlines(Set<PairSymbol> pairs, Set<Interval> intervals);

    /** Fetch historical bars. */
    Mono<List<Candlestick>> fetchKlines(PairSymbol pair, Interval interval,
                                         Instant from, Instant to, int limit);

    /** Current depth via REST (fallback when cache stale). */
    Mono<DepthSnapshot> fetchDepth(PairSymbol pair, int levels);

    /** Current ticker via REST (fallback). */
    Mono<Ticker> fetchTicker(PairSymbol pair);

    /** Exchange info for all supported pairs. */
    Mono<Map<PairSymbol, PairMetadata>> fetchExchangeInfo();

    /** Provider name — used in logs, metrics, HealthResponse. */
    String providerName();
}
```

### 3.2 Binance Implementation Outline

```java
@Component
@ConditionalOnProperty(name = "market.data.provider", havingValue = "binance", matchIfMissing = true)
public class BinanceMarketDataProvider implements MarketDataProvider {

    private final BinanceRestClient rest;
    private final BinanceWebSocketClient ws;

    @Override
    public Flux<TradeObservation> streamTrades(Set<PairSymbol> pairs) {
        return ws.combinedStream(buildStreamNames(pairs, "@trade"))
            .filter(msg -> "trade".equals(msg.get("e").asText()))
            .map(BinanceTradeMapper::toObservation)
            .filter(obs -> obs.price().isPositive() && obs.quantity().isPositive());
    }

    @Override
    public Flux<DepthSnapshot> streamDepth(Set<PairSymbol> pairs) { ... }

    // ... rest of methods
}
```

### 3.3 Provider Wiring

`application.yml`:
```yaml
market:
  data:
    provider: binance        # switch to 'ssi' post-MVP
```

`ProviderConfig`:
```java
@Configuration
class ProviderConfig {
    @Bean
    @ConditionalOnProperty(name = "market.data.provider", havingValue = "binance", matchIfMissing = true)
    BinanceMarketDataProvider binanceProvider(...) { ... }

    // Post-MVP:
    // @ConditionalOnProperty(name = "market.data.provider", havingValue = "ssi")
    // SsiMarketDataProvider ssiProvider(...) { ... }
}
```

All `@Autowired MarketDataProvider` injections resolve to the single active implementation.

### 3.4 What's Provider-Specific vs. Generic

| Concern | Generic (in `application`, `domain`) | Provider-Specific (in `infrastructure.provider.xxx`) |
|---------|-------------------------------------|-----------------------------------------------------|
| Trade observation schema | `TradeObservation` value object | `BinanceTradeEvent` JSON DTO |
| Price/quantity types | `Price`, `Quantity` from exchange-common | BigDecimal parsing from Binance strings |
| WS connection management | Abstract reactive `Flux<T>` | Reconnect logic, ping/pong, URL building |
| Backfill time ranges | `BackfillPlan` with `from`, `to`, `limit` | Binance's 1000-bar-per-request limit, rate throttling |
| Pair symbol | `PairSymbol("BTCUSDT")` | Binance's naming = BTCUSDT (no transform); SSI's VN30F2501 → different provider maps |
| ExchangeInfo refresh interval | Generic @Scheduled (24h) | Binance-specific JSON parse |

**The rule:** if the concept exists in both Binance and SSI worlds (trades, depth, OHLCV), it goes in the generic layer. If it's Binance's encoding/protocol/quirk, it's provider-specific.

---

## 4. Binance Integration Design

### 4.1 REST Client

Single `BinanceRestClient` using WebFlux `WebClient`:

```java
@Component
class BinanceRestClient {
    private final WebClient webClient;      // base URL: https://api.binance.com
    private final RateLimiter restRateLimiter;    // Resilience4j
    private final CircuitBreaker circuitBreaker;

    Mono<BinanceExchangeInfo> getExchangeInfo() {
        return webClient.get().uri("/api/v3/exchangeInfo")
            .retrieve()
            .bodyToMono(BinanceExchangeInfo.class)
            .transformDeferred(RateLimiterOperator.of(restRateLimiter))
            .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
            .retryWhen(Retry.backoff(3, Duration.ofSeconds(1)).filter(this::isRetryable));
    }

    Mono<List<BinanceKlineArray>> getKlines(String symbol, String interval,
                                            long startTimeMs, long endTimeMs, int limit) { ... }

    Mono<BinanceDepthResponse> getDepth(String symbol, int limit) { ... }
}
```

**Rate limiter config** (Resilience4j):
```yaml
resilience4j:
  ratelimiter:
    instances:
      binanceRest:
        limit-for-period: 10          # 10 requests per refresh period
        limit-refresh-period: 1s       # → 600/min; well under Binance's 1200/min IP limit
        timeout-duration: 5s
```

**Timeouts:** connect 2 s, read 10 s (klines batch can be slow).

### 4.2 WebSocket Client

Uses Reactor Netty's `WebSocketClient` for non-blocking WS with backpressure handling.

```java
@Component
class BinanceWebSocketClient {
    private static final String BASE = "wss://stream.binance.com:9443";

    private final ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient();
    private final Sinks.Many<JsonNode> inbound = Sinks.many().multicast().onBackpressureBuffer();

    private final AtomicReference<Disposable> connection = new AtomicReference<>();

    public Flux<JsonNode> combinedStream(Set<String> streamNames) {
        return inbound.asFlux()
            .filter(msg -> msg.has("stream"))
            .filter(msg -> streamNames.contains(msg.get("stream").asText()))
            .map(msg -> msg.get("data"));
    }

    @PostConstruct
    public void start() {
        connect();
    }

    private void connect() {
        var url = BASE + "/stream?streams=" + String.join("/", allStreamNames());
        var handler = (WebSocketHandler) session -> session.receive()
            .map(WebSocketMessage::getPayloadAsText)
            .map(this::parseJson)
            .doOnNext(node -> inbound.tryEmitNext(node))
            .doOnError(err -> log.warn("WS error", err))
            .then();

        var disp = client.execute(URI.create(url), handler)
            .subscribe(
                v -> {},
                err -> scheduleReconnect(err),
                () -> scheduleReconnect(null));
        connection.set(disp);
    }

    private final AtomicInteger backoffAttempt = new AtomicInteger(0);

    private void scheduleReconnect(Throwable err) {
        var delay = computeBackoff(backoffAttempt.incrementAndGet());
        log.warn("WS disconnected, reconnecting in {} — attempt #{}", delay, backoffAttempt.get());
        Mono.delay(delay).subscribe(v -> connect());
    }

    private Duration computeBackoff(int attempt) {
        // 1s, 2s, 4s, 8s, 16s, cap 60s
        var base = Math.min((long) Math.pow(2, attempt - 1), 60);
        return Duration.ofSeconds(base);
    }

    public Set<String> allStreamNames() {
        // Build from supportedPairs × {@trade, @depth20@100ms, @kline_1m (optional)}
    }
}
```

**Reset logic on reconnect:**
1. Reset `backoffAttempt` on successful connection (first inbound message).
2. Emit `MarketDataFeedRecovered` events for all pairs that were in DEGRADED (if outage > 30 s).
3. Fetch fresh `fetchDepth()` for each pair to close any diff gap.

### 4.3 Supported Pairs Configuration

```yaml
market:
  pairs:
    - BTCUSDT
    - ETHUSDT
    - BNBUSDT
    - SOLUSDT
    - XRPUSDT
  intervals:
    - 1m
    - 5m
    - 15m
    - 1h
    - 4h
    - 1d
```

Bound to `@ConfigurationProperties("market")` object. Changing the list requires restart; post-MVP could support runtime add/remove via admin endpoint.

### 4.4 Binance Quirks to Handle

- **Mixed case symbols.** Streams lowercase (`btcusdt@trade`); REST uppercase (`BTCUSDT`). Mapper normalizes to uppercase internally.
- **Kline array format.** `/api/v3/klines` returns arrays, not objects: `[openTime, open, high, low, close, volume, closeTime, quoteVolume, tradeCount, ...]`. Custom Jackson deserializer.
- **Time is milliseconds** for everything. Internally we use `Instant`; mappers convert.
- **Price/quantity are strings** in JSON (to preserve precision). Parse to `BigDecimal`.
- **Depth events have `lastUpdateId`** — useful for diff-based streams; not used for `@depth20@100ms` (snapshots).
- **Ping/pong.** Binance sends ping every 3 min; client must pong within 10 min or get disconnected. Reactor Netty handles this automatically.

---

## 5. Data Model

### 5.1 TimescaleDB — `candlesticks`

Already defined in master `SystemDesign.md` §6.4. Repeated here with implementation notes:

```sql
CREATE TABLE candlesticks (
  pair_symbol   VARCHAR(20)     NOT NULL,
  interval      VARCHAR(5)      NOT NULL,     -- '1m','5m','15m','1h','4h','1d'
  open_time     TIMESTAMPTZ     NOT NULL,
  open          NUMERIC(36,18)  NOT NULL,
  high          NUMERIC(36,18)  NOT NULL,
  low           NUMERIC(36,18)  NOT NULL,
  close         NUMERIC(36,18)  NOT NULL,
  volume        NUMERIC(36,18)  NOT NULL,
  quote_volume  NUMERIC(36,18)  NOT NULL,
  trade_count   INTEGER         NOT NULL,
  close_time    TIMESTAMPTZ     NOT NULL,
  ingested_at   TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
  PRIMARY KEY (pair_symbol, interval, open_time)
);

SELECT create_hypertable(
  'candlesticks', 'open_time',
  chunk_time_interval => INTERVAL '7 days',
  if_not_exists => TRUE
);

-- Most common query: history endpoint
CREATE INDEX ix_cs_pair_interval_time
  ON candlesticks (pair_symbol, interval, open_time DESC);
```

**Write pattern:** JDBC batch insert via `CandlestickJdbcRepository.saveAll()`, using `ON CONFLICT (pair_symbol, interval, open_time) DO UPDATE` for idempotent backfill (Binance klines REST is not strictly immutable — last bar of a batch is "in-progress" and can change on re-fetch):

```sql
INSERT INTO candlesticks (pair_symbol, interval, open_time, open, high, low, close, volume, quote_volume, trade_count, close_time)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
ON CONFLICT (pair_symbol, interval, open_time) DO UPDATE SET
  high         = EXCLUDED.high,
  low          = EXCLUDED.low,
  close        = EXCLUDED.close,
  volume       = EXCLUDED.volume,
  quote_volume = EXCLUDED.quote_volume,
  trade_count  = EXCLUDED.trade_count,
  close_time   = EXCLUDED.close_time,
  ingested_at  = NOW()
WHERE candlesticks.close_time < EXCLUDED.close_time;  -- only update if newer
```

The `WHERE candlesticks.close_time < EXCLUDED.close_time` guard ensures we don't roll back completed bars with stale data from a late-arriving REST response.

**Read pattern:** One query per `/udf/history` request:

```sql
SELECT open_time, open, high, low, close, volume
FROM candlesticks
WHERE pair_symbol = ? AND interval = ? AND open_time BETWEEN ? AND ?
ORDER BY open_time ASC
LIMIT 1000;   -- hard cap per request (SRS §8.5)
```

Post-MVP: continuous aggregates to speed up `/udf/history` for wide ranges and coarse intervals.

### 5.2 Migration Sequence

```
V1__create_candlesticks_hypertable.sql
V2__create_indexes.sql
V3__create_ingestion_log.sql            # optional: track backfill runs (see §9)
V4__enable_compression.sql              # post-MVP; commented out in MVP
```

### 5.3 Redis Key Registry

Confirmed from master §6.3:

| Key | Type | TTL | Content / Operation |
|-----|------|-----|---------------------|
| `md:ticker:<pair>` | String (JSON) | 10 s | `{bestBid, bestAsk, lastPrice, updatedAt}`. Set on every depth or trade event. |
| `md:depth:<pair>` | String (JSON) | 5 s | Top-20 bids + asks `{bids:[[p,q]...], asks:[[p,q]...], updatedAt}`. Replaced fully on each `@depth20@100ms`. |
| `md:exchangeInfo` | Hash | 25 h (refresh at 24 h) | Field per pair → JSON `{tickSize, stepSize, baseAsset, quoteAsset, status}`. |
| `md:kline:<pair>:<interval>:latest` | String (JSON) | 2 min | In-progress (non-closed) candle; updated from WS kline stream if enabled. |
| `md:health:<pair>` | Hash | none | `tradeLastUpdate`, `depthLastUpdate`, `status`. Used by `FeedHealthMonitor`. |
| `md:binance:ws:status` | String | none | `CONNECTED` \| `DISCONNECTED`; atomic markers for operator observability. |

**TTL rationale:**
- **5–10 s on depth/ticker:** serves as a safety net. If the WS stream stops silently (rare — heartbeat catches most), TTL expiry makes the cache empty and fallback to REST kicks in.
- **25 h on exchangeInfo:** refresh loop runs every 24 h; TTL gives 1 h buffer before eviction.
- **No TTL on health:** monitored actively; always present.

### 5.4 Data Flow

```mermaid
flowchart LR
    BWS[Binance WS<br/>combined stream] -->|@trade| TIS[TradeIngestionService]
    BWS -->|@depth20@100ms| DIS[DepthIngestionService]
    BWS -->|@kline_1m<br/>optional| KIS[KlineIngestionService]

    TIS -->|ExternalTradeObserved| K1((Kafka<br/>market-data.events.v1))
    TIS -->|update health| HR[(Redis<br/>md:health:*)]

    DIS -->|snapshot| DR[(Redis<br/>md:depth:*<br/>md:ticker:*)]
    DIS -->|DepthUpdated| K2((Kafka<br/>market-data.depth.v1))

    KIS -->|KlineUpdated| K3((Kafka<br/>market-data.kline.v1))
    KIS -->|in-progress candle| KR[(Redis<br/>md:kline:*)]

    BREST[Binance REST<br/>/klines, /exchangeInfo] --> BF[Backfill<br/>Service]
    BF --> TS[(TimescaleDB<br/>candlesticks)]

    BREST --> ESS[ExchangeInfo<br/>Sync Service]
    ESS --> EIR[(Redis<br/>md:exchangeInfo)]
    ESS -->|PairMetadataUpdated| K1

    FHM[FeedHealthMonitor<br/>@Scheduled 5s] --> HR
    FHM -->|FeedDegraded/Recovered| K1
```

---

## 6. REST API Design

All endpoints implemented with Spring WebFlux (reactive).

### 6.1 Endpoint Summary

| Method | Path | Auth | Consumer | Purpose |
|--------|------|------|----------|---------|
| `GET` | `/udf/config` | None | TradingView chart via FE | UDF config |
| `GET` | `/udf/symbols` | None | TradingView chart | Symbol info |
| `GET` | `/udf/history` | None | TradingView chart | OHLCV bars |
| `GET` | `/api/v1/marketdata/orderbook/{pair}` | User JWT | FE | Depth snapshot for order book UI |
| `GET` | `/api/v1/marketdata/ticker/{pair}` | User JWT | FE | Current ticker |
| `GET` | `/api/v1/marketdata/exchangeInfo` | None | FE | All pairs metadata (UI filters) |
| `GET` | `/internal/ticker/{pair}` | Network-trust | Order Service | Compute freeze for market orders |
| `GET` | `/internal/depth/{pair}?depth=20` | Network-trust | Matching Engine | Walk-the-book |
| `GET` | `/internal/pairs/{pair}/metadata` | Network-trust | Order Service | Tick/step/minNotional |
| `GET` | `/internal/market-data/health` | Network-trust | Matching Engine, Order Service | Per-pair feed health |

### 6.2 UDF Contract Details

The UDF protocol is precise about response shape. Reference: https://github.com/tradingview/charting_library/wiki/UDF

**`/udf/config`:**
```json
{
  "supports_search": true,
  "supports_group_request": false,
  "supported_resolutions": ["1", "5", "15", "60", "240", "1D"],
  "supports_marks": false,
  "supports_timescale_marks": false,
  "supports_time": true,
  "exchanges": [{ "value": "binance-sim", "name": "Binance (Sim)", "desc": "Simulated Binance market" }],
  "symbols_types": [{ "name": "crypto", "value": "crypto" }]
}
```

**Resolution mapping** (TradingView → our interval):
| TV resolution | Our interval |
|---------------|--------------|
| `"1"` | `1m` |
| `"5"` | `5m` |
| `"15"` | `15m` |
| `"60"` | `1h` |
| `"240"` | `4h` |
| `"1D"` | `1d` |

Encapsulated in `ResolutionMapper` (pure function).

**`/udf/symbols?symbol=BTCUSDT`:**
```json
{
  "symbol": "BTCUSDT",
  "name": "BTC/USDT",
  "description": "Bitcoin / Tether",
  "type": "crypto",
  "session": "24x7",
  "timezone": "Etc/UTC",
  "exchange": "binance-sim",
  "minmov": 1,
  "pricescale": 100,
  "has_intraday": true,
  "has_no_volume": false,
  "visible_plots_set": "ohlcv",
  "supported_resolutions": ["1", "5", "15", "60", "240", "1D"]
}
```

`pricescale` derived from pair's `tickSize`: `pricescale = round(1 / tickSize)`. For BTCUSDT tickSize=0.01 → pricescale=100.

**`/udf/history?symbol=BTCUSDT&resolution=1&from=1713600000&to=1713700000&countback=300`:**

Parameters are epoch seconds. `countback` is an optional hint (newer UDF) — server returns up to that many latest bars. Implementation: use `to` as anchor, compute `from_effective = max(from, to - countback * intervalSeconds)`.

Response (compact format — TV's preferred):
```json
{
  "s": "ok",
  "t": [1713600000, 1713600060, 1713600120],
  "o": ["60000.00", "60010.50", "60020.00"],
  "h": ["60050.00", "60025.00", "60040.00"],
  "l": ["59980.00", "60005.00", "60010.00"],
  "c": ["60010.50", "60020.00", "60035.00"],
  "v": ["12.5", "8.3", "15.1"]
}
```

Note: prices/volumes are **strings** in compact format to preserve precision — TV accepts this.

No-data response (no candles in range):
```json
{ "s": "no_data", "nextTime": 1713599000 }
```

`nextTime` hints where data *does* exist (TV uses it to navigate). Return `min(open_time)` in the DB for that pair+interval.

### 6.3 Public Market Data Endpoints

**`GET /api/v1/marketdata/orderbook/{pair}?depth=20`:**

```json
{
  "pair": "BTCUSDT",
  "bids": [["60000.00", "0.5"], ["59999.50", "1.2"]],
  "asks": [["60000.50", "0.3"], ["60001.00", "0.8"]],
  "updated_at": "2026-04-22T10:30:15.123Z"
}
```

Max `depth` = 20. Reads from Redis `md:depth:{pair}`; falls back to `provider.fetchDepth(pair, 20)` on cache miss. If both fail: 503 `DEPTH_UNAVAILABLE`.

**`GET /api/v1/marketdata/ticker/{pair}`:**

```json
{
  "pair": "BTCUSDT",
  "best_bid": "60000.00",
  "best_ask": "60000.50",
  "last_price": "60000.25",
  "updated_at": "2026-04-22T10:30:15.123Z"
}
```

**`GET /api/v1/marketdata/exchangeInfo`:**

Returns full map of supported pairs:
```json
{
  "pairs": [
    {
      "symbol": "BTCUSDT",
      "base_asset": "BTC",
      "quote_asset": "USDT",
      "tick_size": "0.01",
      "step_size": "0.00001",
      "status": "TRADING"
    },
    ...
  ],
  "updated_at": "2026-04-22T00:00:00Z"
}
```

### 6.4 Internal Endpoints

**`GET /internal/ticker/{pair}`:** Same shape as public ticker. No authentication. Used by Order Service for MARKET order freeze calculation.

**`GET /internal/depth/{pair}?depth=20`:** Same as public orderbook. Used by Matching Engine for walk-the-book. Critical latency — must serve from Redis in < 5 ms p99.

**`GET /internal/pairs/{pair}/metadata`:**
```json
{
  "symbol": "BTCUSDT",
  "base_asset": "BTC",
  "quote_asset": "USDT",
  "tick_size": "0.01",
  "step_size": "0.00001",
  "min_notional": "10",
  "status": "TRADING",
  "updated_at": "2026-04-22T00:00:00Z"
}
```

`min_notional` is from local config (hardcoded to 10 USDT in MVP, not from Binance). `tick_size` and `step_size` from Binance exchangeInfo cache.

**`GET /internal/market-data/health`:** See SRS §SR-MD-MD-008. Reads Redis `md:health:*` + `md:binance:ws:status`. Response format already specified there.

### 6.5 Error Handling

Uses same standard error shape as master §5.2.3:
```json
{ "error": { "code": "PAIR_NOT_SUPPORTED", "message": "...", "correlation_id": "...", "timestamp": "..." } }
```

Specific error codes:
| Code | HTTP | Condition |
|------|------|-----------|
| `PAIR_NOT_SUPPORTED` | 404 | Unknown pair |
| `INVALID_RESOLUTION` | 400 | UDF resolution not in supported list |
| `RANGE_TOO_LARGE` | 400 | More than 1000 bars requested |
| `DEPTH_UNAVAILABLE` | 503 | Cache miss + REST fallback failed |
| `TICKER_UNAVAILABLE` | 503 | Same for ticker |
| `FEED_DEGRADED` | 503 | All pairs degraded (rare) |

---

## 7. Kafka Integration

### 7.1 Produced Events

> **⚠️ NOTE (back-ported 2026-06-27 from services/marketdata/DECISIONS.md — supersedes the
> "Outbox (durable)" strategy below for `ExternalTradeObserved`):**
> `ExternalTradeObserved` is now **direct-produced** to `market-data.events.v1` via
> `ephemeralKafkaTemplate` (`acks=1`, no idempotence, fire-and-forget) — it does **not** go through
> the durable `market_data_outbox` + relay anymore. The payload stays wrapped in `EventEnvelope`
> (eventType `ExternalTradeObservedEvent`), so the matching consumer contract is unchanged.
> **`MarketDataFeedDegraded/Recovered` and `PairMetadataUpdated` still use the durable outbox** —
> only the trade firehose changed.
> - **Why the reversal of the original design (§ "Why not just direct-produce…" below):** the real
>   per-pair trade rate is a hundreds/sec **firehose**, while the shared `OutboxRelay` publishes
>   synchronously one-at-a-time (`send().get()`, ~58 msg/s). Write-rate ≫ publish-rate → the outbox
>   grew without bound (observed **8.4M unpublished rows, ~6 days stale**), and because the relay
>   drains FIFO oldest-first, Kafka's tail carried **week-old prices** → Matching filled resting
>   orders at stale prices and live-priced orders never became eligible ("open orders never match").
> - **Trade-off accepted:** trades are ephemeral observations (same class as depth/kline); dropping a
>   few during a Kafka blip is acceptable, and Matching never retroactively fills from buffered trades.
>   This trades the original durability guarantee for **bounded, always-fresh** behavior.
> - **Topic is now a short-retention firehose:** `market-data.events.v1` is declared with
>   `retention.ms=600000` (10 min) + `segment.ms=60000` via a `KafkaAdmin(modifyTopicConfigs=true)`
>   + `NewTopic` in `KafkaConfig` (configurable under `market.kafka.events-topic.*`), so the topic
>   itself can no longer grow unbounded. Consumers of external trades must process **live only**
>   (seek-to-end), never replay — see `SystemDesign_Appendix_MatchingEngine.md §7.2`.

| Event | Topic | Partition Key | Strategy | Volume (est) |
|-------|-------|---------------|----------|--------------|
| `ExternalTradeObserved` | `market-data.events.v1` | `pair` | **Direct produce, ephemeral** (firehose; short topic retention) — *see note above; was "Outbox (durable)"* | hundreds/sec across all pairs |
| `DepthUpdated` | `market-data.depth.v1` | `pair` | Direct produce (ephemeral — one missed = next arrives in 100 ms) | 50/sec per pair |
| `KlineUpdated` | `market-data.kline.v1` | `pair` | Direct produce (ephemeral) | 1/min per pair (or ~1/sec with live bar updates) |
| `MarketDataFeedDegraded` | `market-data.events.v1` | `pair` | Outbox (durable — services must know) | Rare |
| `MarketDataFeedRecovered` | `market-data.events.v1` | `pair` | Outbox (durable) | Rare |
| `PairMetadataUpdated` | `market-data.events.v1` | `pair` | Outbox (durable) | 0–1/day |

**Two publishing strategies:**

1. **Direct produce (no outbox):** For `DepthUpdated` and `KlineUpdated`. These are "ephemeral observations" — losing one doesn't matter because another arrives seconds later. Adding outbox overhead (DB write per event) would harm latency (100 ms pipeline → 200 ms+). Downstream (Gateway for WS fan-out) subscribes with `auto.offset.reset=latest` — no replay of old depth on restart.

2. **Light outbox (durable):** For trade events and feed status. These must not be lost:
   - `ExternalTradeObserved` drives Matching Engine fills — a lost trade = a missed fill.
   - Feed status events drive degraded-mode transitions — a missed Recovered = Matching Engine stays paused forever.

The outbox here is **not** the full transactional outbox of Order/Wallet/Matching (there's no domain-DB transaction to tie it to — Market Data doesn't persist trades). Instead it's a **write-ahead log**:

```sql
CREATE TABLE market_data_outbox (
  id             UUID PRIMARY KEY,
  event_type     VARCHAR(50) NOT NULL,
  topic          VARCHAR(60) NOT NULL,
  partition_key  VARCHAR(64) NOT NULL,
  envelope_json  JSONB NOT NULL,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  published_at   TIMESTAMPTZ NULL,
  attempts       INT NOT NULL DEFAULT 0,
  last_error     TEXT NULL
);
CREATE INDEX ix_md_outbox_unpublished ON market_data_outbox (created_at) WHERE published_at IS NULL;
```

This table lives in TimescaleDB's database (shared infra — though logically separate from candlestick domain). A write here is a simple insert, not part of a larger business txn. The standard `exchange-common` outbox relay publishes and marks.

**Why not just direct-produce `ExternalTradeObserved` and rely on Kafka's durability?** Because the failure scenario is: WS delivers trade → ingestion service tries to publish to Kafka → Kafka unreachable for 30 s → trade lost from memory. With outbox: trade written to DB first (fast, local), then relay publishes async with retry. Trade survives Kafka outages.

### 7.2 Consumed Events

**None.** Market Data Service is a pure producer. It does not consume from any Kafka topic.

(One possible future consumption: `AdminExchangeInfoRefreshRequested` to trigger a manual refresh. Not MVP.)

### 7.3 Kafka Producer Configuration

```yaml
spring:
  kafka:
    producer:
      acks: all
      compression-type: lz4
      batch-size: 16384
      linger-ms: 5                    # 5ms batching; tolerable latency for MVP
      properties:
        enable.idempotence: true
        max.in.flight.requests.per.connection: 5
        retries: 10
        delivery.timeout.ms: 60000
```

For ephemeral events (depth/kline): a separate `KafkaTemplate<String, DepthEvent>` bean with:
- `acks=1` (leader only — faster)
- `compression-type=lz4`
- `linger-ms=0` (don't batch — low latency)

---

## 8. Ingestion Pipeline (Reactive)

The core hot path. Must be non-blocking end-to-end.

### 8.1 Trade Ingestion Flow

```java
@Service
class TradeIngestionService {
    private final MarketDataProvider provider;
    private final MarketDataOutbox outbox;
    private final HealthRedisRepository health;

    @PostConstruct
    public void start() {
        provider.streamTrades(supportedPairs)
            .onBackpressureBuffer(10_000)
            .flatMap(this::publishTradeEvent, 4)        // concurrency 4
            .doOnError(err -> log.error("Trade ingestion pipeline error", err))
            .retry()                                     // restart pipeline on terminal errors
            .subscribe();
    }

    private Mono<Void> publishTradeEvent(TradeObservation obs) {
        return Mono.fromRunnable(() -> {
            // 1. Update health
            health.recordTrade(obs.pair(), obs.observedAt());
            // 2. Write to outbox
            outbox.write(toEnvelope(obs));
        }).subscribeOn(Schedulers.boundedElastic())
          .then();
    }
}
```

**Backpressure:** `onBackpressureBuffer(10_000)` handles the case where DB writes (outbox) lag WS message rate. If buffer exceeds 10 k, we drop oldest (log WARN) — in MVP this should never happen.

**Thread boundaries:**
- Netty event loop threads run the WS client and reactor pipeline.
- DB writes (blocking JDBC) happen on `Schedulers.boundedElastic()` — a thread pool for blocking work.
- Separation prevents blocking I/O from stalling the reactor.

### 8.2 Depth Ingestion Flow

```java
@Service
class DepthIngestionService {
    private final MarketDataProvider provider;
    private final DepthRedisRepository depthCache;
    private final TickerRedisRepository tickerCache;
    private final KafkaTemplate<String, DepthUpdateEvent> kafka;     // ephemeral KafkaTemplate

    @PostConstruct
    public void start() {
        provider.streamDepth(supportedPairs)
            .onBackpressureLatest()                      // only keep latest per pair
            .flatMap(this::process, 4)
            .doOnError(err -> log.error("Depth ingestion error", err))
            .retry()
            .subscribe();
    }

    private Mono<Void> process(DepthSnapshot snap) {
        return depthCache.set(snap.pair(), snap, Duration.ofSeconds(5))
            .then(tickerCache.setFromDepth(snap, Duration.ofSeconds(10)))
            .then(Mono.fromRunnable(() ->
                kafka.send("market-data.depth.v1", snap.pair().value(), toEvent(snap))))
            .then();
    }
}
```

**`onBackpressureLatest`:** If downstream (Redis write) lags, drop intermediate depth updates and keep only the newest. Depth is a snapshot — stale depths have no value.

### 8.3 Kline Ingestion (Optional)

MVP can skip WS kline subscription and rely on periodic REST `/klines` pulls every 30 s. This is simpler but less real-time. If live updates matter:

```java
provider.streamKlines(supportedPairs, Set.of(Interval.ONE_MINUTE))
    .flatMap(kline -> {
        if (kline.isClosed()) {
            // Write to TimescaleDB (upsert)
            return persistence.save(kline.toCandlestick());
        } else {
            // In-progress bar — cache in Redis, publish ephemeral event
            return klineCache.setLatest(kline).then(publishEphemeral(kline));
        }
    })
    .subscribe();
```

**Decision for MVP:** enable kline WS only if time permits. Fallback: `@Scheduled(fixedDelay = 30_000)` polling `/klines` for the most recent bar per pair+interval.

### 8.4 ExchangeInfo Sync

```java
@Service
class ExchangeInfoSyncService {
    private final MarketDataProvider provider;
    private final ExchangeInfoRedisRepository cache;
    private final MarketDataOutbox outbox;

    @Scheduled(fixedRate = 24 * 60 * 60 * 1000, initialDelay = 5_000)   // 24h, first run 5s after startup
    public void refresh() {
        provider.fetchExchangeInfo()
            .flatMap(newInfo -> cache.getAll()
                .flatMap(oldInfo -> {
                    var changes = diff(oldInfo, newInfo);
                    return cache.replaceAll(newInfo)
                        .thenMany(Flux.fromIterable(changes))
                        .flatMap(change -> outbox.write(toPairMetadataUpdated(change)))
                        .then();
                }))
            .doOnError(err -> log.error("ExchangeInfo refresh failed", err))
            .subscribe();
    }
}
```

On first run (startup), `oldInfo` is empty → all pairs emit `PairMetadataUpdated` (this is desirable — Order Service's local copy gets seeded).

---

## 9. Startup & Backfill

### 9.1 Startup Sequence

Managed by `StartupRunner`:

```
Phase 1 — Infra
  1. Connect TimescaleDB, Redis, Kafka. Fail-fast if any unreachable.

Phase 2 — ExchangeInfo
  2. Fetch exchangeInfo from Binance. Populate Redis hash.
     If fails: retry with backoff 5s, 10s, 30s (max 3 attempts).
     If still fails: start in DEGRADED mode (service is up but all pairs unavailable).

Phase 3 — Backfill
  3. For each (pair, interval) in supportedPairs × intervals:
     a. Query max(open_time) from candlesticks.
     b. If null: backfill from target history window (per SRS SR-MD-MD-001:
        30d for 1m, 90d for 1h, 365d for 4h/1d).
     c. Else: backfill from max(open_time) + interval to now.
     Batch requests via Binance 1000-bar limit.

Phase 4 — Live Ingestion
  4. Start WS client, subscribe combined stream.
  5. Start TradeIngestionService, DepthIngestionService, (optional) KlineIngestionService.
  6. Start FeedHealthMonitor @Scheduled.
  7. Start outbox relay.

Phase 5 — Ready
  8. /actuator/health → UP.
```

Readiness indicator:

```java
@Component
class MarketDataReadinessIndicator implements ReactiveHealthIndicator {
    public Mono<Health> health() {
        if (!startupState.isExchangeInfoLoaded()) return Mono.just(Health.down().withDetail("reason","exchange_info").build());
        if (!startupState.isBackfillComplete()) return Mono.just(Health.down().withDetail("reason","backfill").build());
        if (!startupState.isWsConnected()) return Mono.just(Health.down().withDetail("reason","ws_not_connected").build());
        return Mono.just(Health.up().build());
    }
}
```

### 9.2 Backfill Strategy

For each `(pair, interval)`:

```java
public Mono<Void> backfill(PairSymbol pair, Interval interval, Instant targetStart) {
    return candlestickRepo.findLatestOpenTime(pair, interval)
        .defaultIfEmpty(targetStart)
        .flatMapMany(from -> fetchInBatches(pair, interval, from, Instant.now()))
        .flatMap(batch -> candlestickRepo.upsertAll(batch))
        .then();
}

private Flux<List<Candlestick>> fetchInBatches(PairSymbol pair, Interval interval,
                                                 Instant from, Instant to) {
    return Flux.generate(
        () -> from,
        (cursor, sink) -> {
            if (!cursor.isBefore(to)) {
                sink.complete();
                return cursor;
            }
            var batchEnd = cursor.plus(interval.duration().multipliedBy(1000));   // 1000 bars
            var nextCursor = batchEnd.isAfter(to) ? to : batchEnd;
            sink.next(provider.fetchKlines(pair, interval, cursor, nextCursor, 1000).block());
            return nextCursor;
        });
}
```

**Rate limiting:** Binance REST limiter (10/s = 600/min) keeps us well below the 1200/min limit. For initial full backfill of 5 pairs × 6 intervals × ~1–10 batches each = ~60–300 requests → completes in < 1 minute.

### 9.3 Gap Detection (Post-Startup)

A `@Scheduled(fixedDelay = 5 * 60 * 1000)` task runs every 5 min:
- For each (pair, 1m): query `max(open_time)`; if more than 2 min behind wallclock → trigger gap fill.
- This covers the case where WS kline was missed or bars arrived out of order.

### 9.4 Ingestion Log (Optional)

Optional table for operator visibility:

```sql
CREATE TABLE ingestion_log (
  id              BIGSERIAL PRIMARY KEY,
  pair_symbol     VARCHAR(20) NOT NULL,
  interval        VARCHAR(5)  NOT NULL,
  operation       VARCHAR(20) NOT NULL,   -- BACKFILL, GAP_FILL, EXCHANGE_INFO
  from_time       TIMESTAMPTZ NULL,
  to_time         TIMESTAMPTZ NULL,
  bars_inserted   INTEGER NOT NULL,
  started_at      TIMESTAMPTZ NOT NULL,
  completed_at    TIMESTAMPTZ NULL,
  status          VARCHAR(20) NOT NULL,   -- STARTED, OK, FAILED
  error           TEXT NULL
);
```

Not queried by the application; purely for ops. Skip in MVP if time-tight.

---

## 10. Feed Health Monitoring

`FeedHealthMonitor` — the heart of degraded-mode detection.

### 10.1 State Machine per Pair

```
HEALTHY  ──(>2s no update)──▶ STALE  ──(>10s no update)──▶ DEGRADED
   ▲                            ▲                             │
   │                            │                             │
   └────── updates resume ──────┴─────────────────────────────┘
                                                              │
                                                     (>30s or WS closed)
                                                              ▼
                                                        DISCONNECTED
```

### 10.2 Implementation

```java
@Component
class FeedHealthMonitor {
    private final HealthRedisRepository health;
    private final MarketDataOutbox outbox;
    private final Set<PairSymbol> pairs;

    private final Map<PairSymbol, FeedStatus> lastKnownStatus = new ConcurrentHashMap<>();

    @Scheduled(fixedDelay = 5_000, initialDelay = 30_000)    // check every 5s, start after 30s
    public void check() {
        var now = Instant.now();
        for (var pair : pairs) {
            var status = health.getStatus(pair, now);
            var prev = lastKnownStatus.getOrDefault(pair, FeedStatus.HEALTHY);
            if (status != prev) {
                emitTransition(pair, prev, status, now);
                lastKnownStatus.put(pair, status);
            }
        }
    }

    private void emitTransition(PairSymbol pair, FeedStatus from, FeedStatus to, Instant now) {
        if (to == FeedStatus.DEGRADED || to == FeedStatus.DISCONNECTED) {
            if (from == FeedStatus.HEALTHY || from == FeedStatus.STALE) {
                outbox.write(toFeedDegradedEvent(pair, now, to));
            }
        } else if (to == FeedStatus.HEALTHY) {
            if (from == FeedStatus.DEGRADED || from == FeedStatus.DISCONNECTED) {
                outbox.write(toFeedRecoveredEvent(pair, now));
            }
        }
    }
}
```

### 10.3 Status Thresholds

| Status | Condition |
|--------|-----------|
| HEALTHY | `now - lastTradeUpdate <= 2s` |
| STALE | `2s < now - lastTradeUpdate <= 10s` |
| DEGRADED | `now - lastTradeUpdate > 10s` AND WS connected |
| DISCONNECTED | WS connection state = DISCONNECTED |

**STALE is internal-only** — not emitted to Kafka. It's a soft warning for observability.

### 10.4 Special Case: Low-Activity Pairs

Some pairs (e.g., XRPUSDT) may genuinely have < 1 trade/2s during quiet hours. To avoid false DEGRADED:
- Adjust threshold based on historical trade rate (post-MVP).
- MVP: use depth updates (which arrive every 100ms) as a heartbeat — if depth is flowing, feed is fine even if trades are sparse. Update `health.recordHeartbeat(pair)` on every depth event.

Revised `getStatus()`:
```java
// Consider either trade OR depth as a heartbeat
var lastBeat = max(tradeLastUpdate, depthLastUpdate);
```

---

## 11. Configuration

### 11.1 `application.yml`

```yaml
spring:
  application:
    name: market-data-service
  datasource:
    url: jdbc:postgresql://timescaledb:5432/market_data_db
    username: ${DB_USER:market_data_user}
    password: ${DB_PASSWORD}
    hikari:
      maximum-pool-size: 10
  flyway:
    locations: classpath:db/migration
  data:
    redis:
      host: redis
      port: 6379
      lettuce:
        pool:
          max-active: 16
          max-idle: 8
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP:kafka:9092}
    producer:
      acks: all
      compression-type: lz4
      properties:
        enable.idempotence: true

server:
  port: 8085

management:
  endpoints:
    web:
      exposure:
        include: health, info, prometheus
  endpoint:
    health:
      probes:
        enabled: true
      show-details: always

market:
  data:
    provider: binance            # binance | ssi (post-MVP) | tcbs (post-MVP)
  pairs:
    - BTCUSDT
    - ETHUSDT
    - BNBUSDT
    - SOLUSDT
    - XRPUSDT
  intervals:
    - 1m
    - 5m
    - 15m
    - 1h
    - 4h
    - 1d
  backfill:
    initial-history:
      1m: 30d
      5m: 60d
      15m: 90d
      1h: 90d
      4h: 365d
      1d: 365d
    gap-check-interval: 5m
  health:
    check-interval: 5s
    stale-threshold: 2s
    degraded-threshold: 10s
  exchange-info:
    refresh-interval: 24h

binance:
  rest:
    base-url: https://api.binance.com
    connect-timeout: 2s
    read-timeout: 10s
  ws:
    base-url: wss://stream.binance.com:9443
    reconnect:
      initial-delay: 1s
      max-delay: 60s
      multiplier: 2
  kline:
    use-ws: false                # MVP: poll REST; post-MVP: true

outbox:
  relay:
    enabled: true
    poll-interval-ms: 100
    batch-size: 100
    max-attempts: 10

resilience4j:
  ratelimiter:
    instances:
      binanceRest:
        limit-for-period: 10
        limit-refresh-period: 1s
        timeout-duration: 5s
  circuitbreaker:
    instances:
      binanceRest:
        failure-rate-threshold: 50
        sliding-window-size: 20
        wait-duration-in-open-state: 30s

logging:
  pattern:
    level: "%5p [%X{correlation_id:-},%X{pair:-}]"
  level:
    root: INFO
    com.haizz.exchange.marketdata: DEBUG
    com.haizz.exchange.marketdata.infrastructure.provider.binance: INFO   # quieter hot path
    reactor.netty.http.client: WARN
```

### 11.2 JVM Tuning

Reactive services benefit from more heap for buffers, less from large memory pages:

```
-Xms512m -Xmx1024m
-XX:+UseG1GC
-XX:MaxGCPauseMillis=100
-Dreactor.netty.ioWorkerCount=4
```

---

## 12. Error Handling & Edge Cases

### 12.1 Exception Hierarchy

```
MarketDataException (from exchange-common)
├── PairNotSupportedException       → 404
├── InvalidResolutionException       → 400
├── RangeTooLargeException           → 400
├── DepthUnavailableException        → 503
├── TickerUnavailableException       → 503
└── ProviderUnavailableException     → 503 (internal — circuit breaker open)
```

### 12.2 Edge Cases

| Scenario | Behavior |
|----------|----------|
| Binance WS returns malformed JSON | Log WARN, skip message, continue |
| Trade event with `price = 0` or `quantity = 0` | Log WARN, skip (don't publish) |
| Depth event with fewer than expected levels | Accept as-is (Binance may return less during extreme volatility) |
| `/udf/history` for unsupported pair | 404 `PAIR_NOT_SUPPORTED` |
| `/udf/history` for unsupported resolution | 400 `INVALID_RESOLUTION` |
| `/udf/history` requesting 5000 bars | 400 `RANGE_TOO_LARGE` |
| `/udf/history` range has no data | 200 `{s:"no_data", nextTime}` |
| Binance REST 429 rate limited | Resilience4j rate limiter prevents it upstream; if it slips through: exponential backoff, log WARN |
| Binance REST 418 IP banned | Stop all REST calls for duration in `Retry-After`, emit `MarketDataFeedDegraded` for all pairs, alert |
| WS disconnect | Exponential backoff reconnect; if > 30 s, emit `MarketDataFeedDegraded` |
| WS reconnect after short blip (< 30 s) | No Degraded event; resume silently |
| WS reconnect after long outage | Emit `MarketDataFeedRecovered`; fetch fresh depth/exchangeInfo |
| TimescaleDB down during ingestion | Outbox writes fail → service pauses trade publishing; health DOWN; backfill pauses |
| Redis down | Ticker/depth cache unavailable → internal endpoints fall back to REST; public endpoints return stale data with `stale: true` flag |
| Kafka down | Outbox accumulates; relay retries; consumers experience no data but no corruption |

### 12.3 Client-Facing Error Behavior

For public endpoints (`/api/v1/marketdata/*`, `/udf/*`), keep error messages generic to avoid information leakage. Internal endpoints can include more detail (called only by trusted services).

For UDF specifically: TV expects `{s:"error", errmsg:"..."}` on recoverable errors. On `/udf/history` failure: return `{s:"error", errmsg:"Failed to load history"}` with 200 OK — TV handles it gracefully.

---

## 13. Testing Strategy

Market Data differs from Order/Matching: less algorithmic complexity, more external-integration concerns.

### 13.1 Test Pyramid

| Layer | Count | Framework | What's Tested |
|-------|-------|-----------|---------------|
| Unit — domain | ~20 | JUnit 5 | Value objects, FeedStatus transitions, ResolutionMapper |
| Unit — mappers | ~20 | JUnit 5 | `BinanceTradeMapper`, `BinanceKlineMapper`, etc. (table-driven with captured Binance fixtures) |
| Unit — ingestion | ~15 | Reactor-Test (StepVerifier) | Trade/depth pipelines with mocked provider |
| Integration — persistence | ~10 | Testcontainers TimescaleDB | Hypertable creation, upsert semantics, range queries |
| Integration — cache | ~10 | Testcontainers Redis | TTL behavior, hash operations, concurrent updates |
| Integration — WS | ~8 | Embedded WS server (in-process) | Connect, receive, disconnect, reconnect, backoff |
| Integration — UDF | ~10 | WebTestClient | Full controller with real DB; compare response against TV spec |
| E2E | ~5 | Testcontainers all + mocked Binance | Startup backfill completes; trade ingestion publishes |

### 13.2 Binance Fixtures

Capture real Binance responses once, store as JSON fixtures in `src/test/resources/fixtures/binance/`:

```
fixtures/binance/
├── exchangeInfo.json          # full response
├── klines_btcusdt_1m_1000.json
├── ws_trade_btcusdt.json      # single message
├── ws_trade_batch.json         # 100 messages
├── ws_depth20_btcusdt.json
└── ws_kline_btcusdt_1m.json
```

Tests use these to verify mappers without touching Binance. Refresh fixtures quarterly (they shouldn't change, but validate).

### 13.3 Embedded WS Server for Integration Tests

```java
@TestConfiguration
class EmbeddedBinanceWs {
    @Bean(destroyMethod = "close")
    public WebSocketServer embeddedServer() {
        return new WebSocketServer(9443, session -> {
            // Replay fixture file based on subscription
            return session.send(loadFixtureMessages(session.getRequestUri()));
        });
    }
}
```

Tests point `binance.ws.base-url` to `ws://localhost:9443` via profile config.

### 13.4 Critical Test Scenarios

**Ingestion:**
- `tradeIngestion_validTrade_publishesToOutbox_updatesHealthTimestamp`
- `tradeIngestion_invalidPrice_isDroppedAndLogged`
- `tradeIngestion_highVolume_backpressureBuffersCorrectly`
- `depthIngestion_newSnapshot_overwritesRedis_updatesTicker_publishesKafka`

**UDF:**
- `udfHistory_validRange_returnsCorrectBarCountInCompactFormat`
- `udfHistory_emptyRange_returnsNoData`
- `udfHistory_exceedsMaxBars_returns400RangeTooLarge`
- `udfSymbols_supportedPair_returnsCorrectPricescale`

**Feed health:**
- `feedMonitor_noTradesFor15s_emitsDegraded`
- `feedMonitor_tradesResumeAfterDegraded_emitsRecovered`
- `feedMonitor_wsDisconnected_emitsDisconnected`
- `feedMonitor_depthHeartbeat_preventsFalseDegradedForLowTradeVolume`

**Backfill:**
- `startupBackfill_emptyDb_fetchesFullInitialHistory`
- `startupBackfill_partialDb_fetchesOnlyGap`
- `gapCheck_detectsMissingBar_triggersFill`

**WS reconnect:**
- `wsClient_disconnected_reconnectsWithBackoff_1s_2s_4s`
- `wsClient_reconnectSucceeds_resetsBackoffCounter`
- `wsClient_persistentFailure_doesNotOverflow_capsAt60s`

**Provider abstraction:**
- ArchUnit test: no Binance* class referenced outside the binance package
- Swap test: with a mock `MarketDataProvider`, the app works identically (no Binance dependency at runtime in the test)

---

## 14. Open Implementation Notes

1. **Kline WS vs REST polling.** MVP config defaults to REST polling (30 s). Enable WS when confident about WS stability. Either works; WS gives cleaner real-time chart updates.

2. **Ticker source.** Currently derived from depth events (`bids[0]`, `asks[0]`). Alternative: subscribe Binance's `@bookTicker` stream (dedicated best bid/ask). Choose whichever has better stream reliability post-launch.

3. **Reactive or imperative?** Recommended reactive throughout (WebFlux). Alternative: imperative MVC with a separate Netty WS thread. Reactive is a better fit for the ingestion workload but has a steeper learning curve. User memory notes WebClient already in use — stick with reactive.

4. **Compression on Kafka.** MVP uses lz4 everywhere. zstd (higher compression, slightly more CPU) is worth revisiting post-MVP if disk usage matters.

5. **Continuous aggregates.** Post-MVP optimization: `CREATE MATERIALIZED VIEW candlesticks_1h_agg WITH (timescaledb.continuous) AS SELECT time_bucket('1h', open_time) ...`. Makes `/udf/history?resolution=60` fast even on years of 1m data.

6. **Compression policy.** TimescaleDB native columnar compression reduces candlesticks by ~10×. Apply when data size exceeds 1 GB:
   ```sql
   ALTER TABLE candlesticks SET (timescaledb.compress, timescaledb.compress_segmentby = 'pair_symbol, interval');
   SELECT add_compression_policy('candlesticks', INTERVAL '7 days');
   ```

7. **Trade history persistence.** If downstream (e.g., UI "Market Trades" tab) needs recent trade list: two options — (a) subscribe to `market-data.events.v1` in Gateway/Trade-History-Service, (b) add a `recent_trades` Redis LIST with LPUSH/LTRIM to keep last N. Option (b) is simpler for MVP; defer until needed.

8. **Provider selection at runtime.** MVP: provider fixed at boot via `market.data.provider` property. Post-MVP could switch via admin endpoint — but requires disconnecting WS and reconnecting to new provider, not trivial. Skip.

9. **Multi-provider hybrid.** SSI for VN equities + Binance for crypto simultaneously. The current abstraction allows this via a `MarketDataProvider` per asset class, indexed by pair's asset type. Adds complexity; post-MVP.

10. **WS heartbeat logic.** Binance uses ping/pong at TCP level — Reactor Netty handles. If Binance stops sending data but TCP is alive, detection is via app-level timestamps (our health monitor). Both layers work together.

---

*End of `SystemDesign_Appendix_MarketDataService.md`.*
