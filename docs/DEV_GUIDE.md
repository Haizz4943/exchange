
# Development Guide

**Project Name:** Simulated Crypto Trading Platform — *Haizz Exchange*
**Version:** 1.0
**Date:** April 25, 2026
**Author:** Haizz (Product Owner & Developer)
**Related Documents:** `BRD.md` v1.0, `SRS.md` v1.0, `SystemDesign.md` v1.0, all `SystemDesign_Appendix_*.md`

---

## 0. How to Read This Guide

This is the bridge between System Design and code. It tells you **what to build first**, **how to organize it**, and **what conventions to follow**. It does not repeat architecture rationale (see `SystemDesign.md`) or requirements (see `SRS.md`).

Reading order: §1 (repo layout) → §2 (coding standards) → §3 (implementation roadmap) → build. Cài đặt môi trường dev xem [`GETTING_STARTED.md`](../GETTING_STARTED.md). Khái niệm event-driven (outbox, backlog, Kafka consumer/lag) & cách tự chẩn đoán "event không tới" xem [`GLOSSARY.md`](GLOSSARY.md).

---

## 1. Project Structure

### 1.1 Repository Layout

Single Git monorepo. All backend services + shared library + frontend in one repository.

```
exchange/
├── pom.xml                              # Maven parent POM (BOM, version pins)
│
├── exchange-common/                     # Shared library module
│   ├── pom.xml
│   └── src/main/java/com/haizz/exchange/common/
│       ├── enums/                       # OrderSide, OrderStatus, OrderType, TradeRole, AssetCode, WalletTxnType
│       ├── value/                       # Money, Price, Quantity, Pair (immutable, BigDecimal-backed)
│       ├── event/
│       │   ├── order/                   # OrderPlacedEvent, OrderCancelRequestedEvent, ...
│       │   ├── trade/                   # TradeExecutedEvent, OrderFilledEvent, ...
│       │   ├── wallet/                  # WalletTransactionEvent
│       │   ├── user/                    # UserRegisteredEvent
│       │   └── market/                  # ExternalTradeObservedEvent, MarketDataFeedDegradedEvent, ...
│       ├── outbox/                      # OutboxEntity, OutboxRelay abstract, OutboxPublisher interface
│       ├── web/                         # CorrelationIdFilter, ErrorResponse POJO, BaseException
│       └── kafka/                       # TopicNames constants, KafkaHeaders constants
│
├── services/
│   ├── auth-service/                    # Port 8081
│   │   ├── pom.xml
│   │   ├── Dockerfile
│   │   └── src/main/java/com/haizz/exchange/auth/
│   │       ├── api/                     # Controllers, DTOs
│   │       ├── application/             # Use cases (RegisterUseCase, LoginUseCase, ...)
│   │       ├── domain/                  # User, Credential, Session entities + value objects
│   │       ├── infrastructure/          # JPA repos, Kafka producer, outbox relay, Redis rate limiter
│   │       └── config/                  # SecurityConfig, JwtConfig, AppConfig
│   │
│   ├── wallet-service/                  # Port 8082
│   │   ├── pom.xml
│   │   ├── Dockerfile
│   │   └── src/main/java/com/haizz/exchange/wallet/
│   │       ├── api/
│   │       ├── application/
│   │       ├── domain/                  # Wallet, WalletTransaction, DepositRecord, WithdrawalRecord
│   │       ├── infrastructure/
│   │       └── config/
│   │
│   ├── order-service/                   # Port 8083
│   │   ├── pom.xml
│   │   ├── Dockerfile
│   │   └── src/main/java/com/haizz/exchange/order/
│   │       ├── api/
│   │       ├── application/
│   │       ├── domain/                  # Order, Asset, TradingPair, FeeSchedule
│   │       ├── infrastructure/
│   │       └── config/
│   │
│   ├── matching-engine/                 # Port 8084
│   │   ├── pom.xml
│   │   ├── Dockerfile
│   │   └── src/main/java/com/haizz/exchange/matching/
│   │       ├── application/             # No REST API for users — Kafka-driven
│   │       ├── domain/                  # Trade, fill computation, in-memory index
│   │       ├── infrastructure/
│   │       └── config/
│   │
│   ├── market-data-service/             # Port 8085
│   │   ├── pom.xml
│   │   ├── Dockerfile
│   │   └── src/main/java/com/haizz/exchange/marketdata/
│   │       ├── api/                     # UDF endpoints, public + internal REST
│   │       ├── application/
│   │       ├── domain/                  # Candlestick, FeedStatus
│   │       ├── infrastructure/          # Binance client, TimescaleDB repo, Redis cache, WS subscriber
│   │       └── config/
│   │
│   └── gateway/                         # Port 8080
│       ├── pom.xml
│       ├── Dockerfile
│       └── src/main/java/com/haizz/exchange/gateway/
│           ├── config/                  # Route definitions, CORS, JWT filter
│           ├── filter/                  # Auth filter, rate limit filter, correlation-id filter
│           └── ws/                      # WebSocket handler, Kafka consumer → WS fan-out
│
├── frontend/                            # Port 3000
│   ├── package.json
│   ├── next.config.js
│   ├── tsconfig.json
│   ├── Dockerfile
│   └── src/
│       ├── app/                         # Next.js App Router (standalone mode)
│       │   ├── layout.tsx
│       │   ├── page.tsx                 # redirect to /trade
│       │   ├── login/page.tsx
│       │   ├── trade/page.tsx
│       │   ├── wallet/page.tsx
│       │   ├── orders/page.tsx
│       │   └── trades/page.tsx
│       ├── panel/                       # Embeddable entry point (Stage 2)
│       │   ├── HaizzTradingPanel.tsx    # Root component — default export
│       │   ├── PanelRouter.tsx          # Internal navigation (no URL changes)
│       │   └── index.ts                 # Public API surface
│       ├── features/
│       │   ├── auth/
│       │   ├── trade/                   # TradeScreen, OrderForm, OrderBook, TradesTape, PairSelector
│       │   ├── wallet/                  # WalletOverview, DepositDialog, WithdrawDialog
│       │   ├── orders/                  # OpenOrdersTable, OrderHistoryTable
│       │   ├── chart/                   # CandlestickChart.tsx, useChartData.ts, chartConfig.ts
│       │   └── trades/                  # TradeHistoryTable
│       └── lib/
│           ├── api/                     # Fetch wrapper, typed endpoints, error normalization
│           ├── ws/                      # WsClient, useWsSubscription, WsProvider
│           ├── auth/                    # TokenStore, AuthBridge (standalone + embedded)
│           ├── store/                   # Zustand stores
│           └── config/                  # PanelConfigProvider, env helpers
│
├── infra/
│   ├── postgres/
│   │   └── init/
│   │       └── 01-create-databases.sh   # Creates auth_db, wallet_db, order_db, match_db
│   └── kafka/
│       └── create-topics.sh             # Explicit topic creation for prod profile
│
├── docker-compose.yml
├── docker-compose.dev.yml               # Dev overrides (expose PG/Kafka ports to host)
├── .env.example
├── .gitignore
└── README.md
```

### 1.2 Module Breakdown

| Module | Responsibility | Port | Owned DB | Owner |
|--------|---------------|------|----------|-------|
| `exchange-common` | Enums, value objects, event schemas, outbox framework, web filters | — | None | Shared |
| `auth-service` | Registration, login, JWT issuance, refresh tokens, SSO-ready | 8081 | `auth_db` | Haizz |
| `wallet-service` | Balances, freeze/unfreeze, deposits, withdrawals, audit log | 8082 | `wallet_db` | Haizz |
| `order-service` | Order lifecycle, validation, reference data (Asset, TradingPair) | 8083 | `order_db` | Haizz |
| `matching-engine` | Simulated fill execution, Trade persistence | 8084 | `match_db` | Haizz |
| `market-data-service` | Binance ingestion, OHLCV, UDF endpoints, depth cache | 8085 | `marketdata_db` (TimescaleDB) | Haizz |
| `gateway` | HTTP routing, JWT validation, WS fan-out, rate limiting | 8080 | None (stateless) | Haizz |
| `frontend` | UI, TradingView chart, embeddable panel | 3000 | None | Haizz |

### 1.3 Package Convention per Service

Every backend service follows the same 5-package structure:

```
com.haizz.exchange.<service>/
├── api/              # @RestController, request/response DTOs, validation
├── application/      # Use cases (@Service, @Transactional), orchestration logic
├── domain/           # @Entity, value objects, enums, domain exceptions
├── infrastructure/   # JPA repositories, Kafka producers/consumers, external clients, outbox relay
└── config/           # @Configuration classes, Spring Security, AppProperties
```

**Rules:**

- `domain` must NOT import Spring, JPA, or Jackson annotations. Pure Java objects. (Exception: JPA `@Entity`/`@Id` on entities — pragmatic compromise for MVP. Enforce via ArchUnit.)
- `api` must NOT import `infrastructure` directly.
- `application` is the only layer that orchestrates multiple concerns (domain + infrastructure).
- DTOs live in `api`, never in `domain`. Entities are never exposed in API responses.

---

## 2. Coding Standards

### 2.1 Naming Conventions

| Element                  | Convention                    | Example                                      |
| ------------------------ | ----------------------------- | -------------------------------------------- |
| Java classes             | PascalCase                    | `OrderPlacementUseCase`, `WalletTransaction` |
| Java methods/variables   | camelCase                     | `freezeBalance()`, `availableAmount`         |
| Java constants           | UPPER_SNAKE_CASE              | `MAX_RETRY_ATTEMPTS`, `DEFAULT_FEE_RATE`     |
| DB tables                | snake_case                    | `wallet_transactions`, `trading_pairs`       |
| DB columns               | snake_case                    | `created_at`, `order_id`                     |
| API endpoints (public)   | kebab-case                    | `/api/v1/market-data/orderbook/{pair}`       |
| API endpoints (internal) | kebab-case                    | `/internal/market-data/health`               |
| Kafka topics             | dot-separated, versioned      | `orders.events.v1`, `market-data.events.v1`  |
| Kafka event types        | PascalCase                    | `OrderPlacedEvent`, `TradeExecutedEvent`     |
| React components         | PascalCase                    | `CandlestickChart`, `OrderForm`              |
| React hooks              | camelCase with `use` prefix   | `useChartData`, `useWsSubscription`          |
| CSS classes (FE)         | `haizz-` prefix + CSS Modules | `.haizz-TradeScreen__orderBook`              |
| Environment variables    | UPPER_SNAKE_CASE              | `SPRING_DATASOURCE_URL`, `JWT_SIGNING_KEY`   |

### 2.2 Code Organization Rules

1. **DTOs for all API boundaries.** Never expose `@Entity` directly in REST responses. Map with manual mappers or MapStruct.
2. **Business logic in `application/` layer.** Controllers are thin — validate input, delegate to use case, return response.
3. **One use case per file.** `PlaceOrderUseCase.java`, not a god-class `OrderService.java` with 30 methods. Each use case class has one public method.
4. **No business logic in repositories.** Repositories do CRUD + named queries only.
5. **All monetary values use `BigDecimal`.** Never `double` or `float`. In JSON, serialize as strings to prevent JS float precision loss.
6. **UUIDs for all entity IDs.** No sequential integer PKs. Declare as `UUID` type in Java, `UUID` column type in PostgreSQL.

### 2.3 Error Handling

**Global exception handler per service** via `@RestControllerAdvice`:

```java
// exchange-common: shared error response shape
public record ErrorResponse(
    String code,          // e.g., "ORDER_VALIDATION_FAILED"
    String message,       // human-readable
    String correlationId, // from MDC
    Instant timestamp,
    Map<String, String> details  // field-level errors (optional)
) {}
```

**Exception hierarchy** (in `exchange-common`):

```
BaseException (RuntimeException)
├── NotFoundException            → 404
├── ConflictException            → 409
├── ValidationException          → 400
├── UnauthorizedException        → 401
├── ForbiddenException           → 403
└── ServiceUnavailableException  → 503
```

Each service extends with domain-specific exceptions:

```
// order-service
OrderValidationException extends ValidationException
InsufficientBalanceException extends ConflictException

// market-data-service
PairNotSupportedException extends NotFoundException
DepthUnavailableException extends ServiceUnavailableException
```

### 2.4 Logging

**Format:** Structured JSON via Logback + `logstash-logback-encoder`.

**MDC fields** (set in `CorrelationIdFilter` from `exchange-common`):

- `correlationId` — UUID, propagated via `X-Correlation-Id` header and Kafka message headers.
- `userId` — extracted from JWT by Gateway, forwarded as `X-User-Id` header.
- `service` — service name from `spring.application.name`.

**Log level guidelines:**

| Level | When |
|-------|------|
| `ERROR` | Unhandled exceptions, broken invariants (e.g., negative balance), outbox dead-letter |
| `WARN` | Retries exhausted, feed degradation, rate limit exceeded, circuit breaker open |
| `INFO` | Business events — order placed, user registered, deposit confirmed, trade executed |
| `DEBUG` | Detailed flow for troubleshooting — off by default, toggleable per service |

**Never log:** passwords, JWT tokens (full), card data. Redact `Authorization` header to `Bearer ***`.

### 2.5 Git Conventions

**Branching model:** Trunk-based development with short-lived feature branches (solo dev — keep it simple).

```
main
├── feat/auth-login
├── feat/wallet-freeze
├── bugfix/depth-cache-ttl
└── hotfix/jwt-expiry
```

**Commit message format:** Conventional Commits.

```
feat(order): implement limit order placement with validation
fix(market-data): handle Binance WS reconnect race condition
chore(exchange-common): add TradeExecutedEvent schema v1
refactor(wallet): extract freeze/unfreeze to dedicated use case
test(matching): add walk-the-book partial fill tests
docs: update DEV_GUIDE with Kafka topic catalog
```

**PR process:** Self-review checklist (solo dev):

- [ ] for claude's code code, write the summary for commit message
- [ ] Tests pass locally (`mvn verify` / `npm test`)
- [ ] No `System.out.println` or `console.log` left
- [ ] New endpoints documented in service README
- [ ] Kafka event changes reflected in `exchange-common`

---

## 3. Implementation Roadmap

Build order is dictated by dependency graph and risk. Build foundational pieces first, risky integrations early.

### Phase 0: Foundation (Week 1)

- [ ] Initialize Maven parent POM with version pins (Spring Boot 3.3.x, Java 21, Spring Cloud 2023.0.x)
- [ ] Scaffold `exchange-common` module: enums, value objects, event POJOs, outbox framework, `CorrelationIdFilter`, `ErrorResponse`
- [ ] Set up `docker-compose.yml` with all infrastructure (PostgreSQL, TimescaleDB, Redis, Kafka)
- [ ] Create `infra/postgres/init/01-create-databases.sh`
- [ ] Create `.env.example` with placeholder secrets
- [ ] Verify `docker-compose up` brings all infra up healthy
- [ ] Create Dockerfile template for Spring Boot services (multi-stage build)

**Exit criteria:** `mvn install` succeeds on `exchange-common`; `docker-compose up` starts all infra; connect to each DB via client.

### Phase 1: Auth + Wallet Core (Weeks 2–3)

- [ ] **Auth Service** — scaffold module, Flyway migrations, domain entities
- [ ] Auth: `POST /auth/register`, `POST /auth/login`, `POST /auth/refresh`, `POST /auth/logout`, `GET /auth/me`
- [ ] Auth: JWT RS256 issuance, refresh token rotation, rate limiting (Redis)
- [ ] Auth: Publish `UserRegistered` via outbox → Kafka
- [ ] Auth: Unit tests (use case logic) + integration tests (Testcontainers)
- [ ] **Wallet Service** — scaffold module, Flyway migrations, domain entities
- [ ] Wallet: Consume `UserRegistered` → auto-provision wallets (USDT, BTC, ETH, BNB, SOL)
- [ ] Wallet: `GET /wallets`, `POST /wallets/deposit`, `POST /wallets/withdraw`
- [ ] Wallet: Internal endpoints: `POST /internal/wallets/freeze`, `POST /internal/wallets/unfreeze`, `POST /internal/wallets/settle-trade`
- [ ] Wallet: Balance invariant enforcement (`total = available + frozen` as CHECK constraint)
- [ ] Wallet: `DataSeeder` for dev profile — seed initial USDT balance on first registered user
- [ ] Wallet: Unit tests + integration tests

**Exit criteria:** Register → login → get JWT → view wallets → deposit → see updated balance. End-to-end via Postman/Insomnia.

### Phase 2: Market Data Service Expansion (Week 3–4)

Market Data Service REST scaffold already exists. Expand with:

- [ ] Binance WebSocket subscriber (`@trade`, `@depth20@100ms` for 5 pairs)
- [ ] `ExternalTradeObserved` Kafka publishing
- [ ] Redis depth cache (top 20 per side, TTL 5s)
- [ ] Redis ticker cache (best bid/ask, TTL 5s)
- [ ] `FeedHealthMonitor` (`@Scheduled` 5s, publish `MarketDataFeedDegraded`/`Recovered`)
- [ ] Internal endpoints: `/internal/ticker/{pair}`, `/internal/depth/{pair}`, `/internal/pairs/{pair}/metadata`
- [ ] Public endpoint: `/api/v1/marketdata/orderbook/{pair}`
- [ ] Verify UDF endpoints still work with TradingView Lightweight Charts
- [ ] Integration tests with WireMock for Binance REST

**Exit criteria:** WS connected to Binance, depth cache populated, `/internal/ticker/BTCUSDT` returns live best bid/ask.

### Phase 3: Order Service (Weeks 4–5)

- [ ] Scaffold module, Flyway migrations
- [ ] Reference data: `Asset`, `TradingPair`, `FeeSchedule` entities + seed via Flyway
- [ ] Order placement: validate → sync call to Wallet (freeze) → sync call to Market Data (ticker/metadata) → persist → outbox
- [ ] Order cancellation: validate ownership → state transition → outbox → Wallet unfreeze
- [ ] Order state machine: `NEW` → `OPEN` → `PARTIALLY_FILLED` → `FILLED` / `CANCELLED` / `EXPIRED`
- [ ] Kafka consumer: `TradeExecuted` → update order state (fill quantity, remaining)
- [ ] REST API: `POST /orders`, `DELETE /orders/{id}`, `GET /orders`, `GET /orders/{id}`
- [ ] Idempotency key support on `POST /orders`
- [ ] Unit tests (validation, state machine) + integration tests

**Exit criteria:** Place market order → freeze balance → `OrderPlaced` event on Kafka → order visible in `GET /orders`.

### Phase 4: Matching Engine (Weeks 5–6)

- [ ] Scaffold module, Flyway migrations
- [ ] Cold-start: load open orders from Order Service internal API at boot
- [ ] In-memory index: per-pair, per-side `TreeMap<Price, TreeMap<CreatedAtMicros, Order>>`
- [ ] Consume `OrderPlaced` → add to index
- [ ] Consume `OrderCancelled` → remove from index
- [ ] Consume `ExternalTradeObserved` → evaluate limit orders against external price
- [ ] Market order: fill immediately at best bid/ask (walk-the-book if quantity exceeds top level)
- [ ] Limit order: rest in index; fill when external price crosses limit
- [ ] Partial fill support
- [ ] Fee calculation (maker/taker from `FeeSchedule`)
- [ ] Publish `TradeExecuted` → consumed by Order Service + Wallet Service
- [ ] Idempotency: Redis dedup set for processed event IDs (TTL 24h)
- [ ] Unit tests (fill computation, walk-the-book, partial fill) + integration tests

**Exit criteria:** Place market order → Matching Engine fills it → `TradeExecuted` → Order Service updates state → Wallet Service settles (debit frozen + credit received asset). Full loop verified.

### Phase 5: Gateway (Week 6–7)

- [ ] Spring Cloud Gateway scaffold
- [ ] Route configuration: path-prefix → service URL mapping
- [ ] JWT validation filter (verify RS256 with Auth Service's public key)
- [ ] Rate limiting filter (Redis, 60 RPS/user sustained, 120 burst)
- [ ] CorrelationId filter (generate if missing, propagate)
- [ ] CORS configuration (allow frontend origin)
- [ ] WebSocket handler: subscribe to Kafka topics, fan-out to authenticated WS connections
- [ ] WS channels: price ticks, order updates (per user), wallet balance updates (per user)
- [ ] Health aggregation: proxy `/actuator/health` from all downstream services

**Exit criteria:** All REST calls routed through Gateway with JWT validation. WebSocket connection established, receives live price ticks.

### Phase 6: Frontend MVP (Weeks 7–9)

- [ ] Next.js project scaffold (App Router, TypeScript, Tailwind CSS)
- [ ] Auth screens: Login, Register
- [ ] API client layer (`lib/api/`) with typed endpoints, error normalization, auto-refresh on 401
- [ ] WebSocket manager (`lib/ws/`) — single connection, subscription hooks
- [ ] **TradingView chart**: `CandlestickChart.tsx` wrapping Lightweight Charts v5
  - [ ] `useChartData` hook: fetch `/udf/history`, subscribe to WS kline updates
  - [ ] Resolution selector (1m, 5m, 15m, 1h, 4h, 1d)
- [ ] **Trade screen**: chart + order book + order form + open orders + pair selector
- [ ] **Order book**: depth visualization (bids/asks, quantities, cumulative)
- [ ] **Order form**: market/limit toggle, buy/sell, quantity, price (limit), available balance display
- [ ] **Wallet overview**: balances per asset, deposit/withdraw dialogs
- [ ] **Order history table**: sortable, filterable
- [ ] **Trade history table**
- [ ] Zustand stores: auth, wallet, orders, market data
- [ ] `HaizzTradingPanel` component with `mode` prop for Stage 2 embedding
- [ ] CSS scoping with `haizz-` prefix, no global styles
- [ ] Responsive ≥ 1024px

**Exit criteria:** Full user journey in browser: register → login → view chart → place order → see fill → check wallet balance updated.

### Phase 7: Integration & Polish (Week 9–10)

- [ ] End-to-end smoke tests (full docker-compose stack)
- [ ] Embedding spike: mount `HaizzTradingPanel` in a dummy Next.js host app, verify no CSS collisions
- [ ] Fix edge cases: WS reconnection, stale data indicators, error toasts
- [ ] Performance check: chart load < 2s, order placement < 500ms
- [ ] README per service (purpose, APIs, Kafka topics, local dev)
- [ ] Root README with architecture diagram and getting-started

---

> **Environment setup** (prerequisites, Docker, chạy từng service, test API): xem [`GETTING_STARTED.md`](../GETTING_STARTED.md).

---

## 4. Dependency Management

### 4.1 Maven Parent POM (Version Pins)

```xml
<properties>
    <java.version>21</java.version>
    <spring-boot.version>3.3.4</spring-boot.version>
    <spring-cloud.version>2023.0.3</spring-cloud.version>
    <resilience4j.version>2.2.0</resilience4j.version>
    <testcontainers.version>1.20.1</testcontainers.version>
    <flyway.version>10.x</flyway.version>
    <mapstruct.version>1.5.x</mapstruct.version>
</properties>
```

All service modules declare dependencies against the parent BOM — no ad-hoc version overrides unless documented as ADR.

### 4.2 Key Backend Dependencies

| Dependency | Version | Purpose | Notes |
|-----------|---------|---------|-------|
| Spring Boot Starter Web | 3.3.x | REST APIs | All services except Gateway |
| Spring Boot Starter WebFlux | 3.3.x | Reactive HTTP client, WS client | Market Data + Gateway |
| Spring Cloud Gateway | 2023.0.x | API Gateway | Gateway only |
| Spring Boot Starter Data JPA | 3.3.x | ORM | All services with PG |
| Spring Boot Starter Security | 3.3.x | Auth + JWT | Auth Service + Gateway |
| Spring Kafka | 3.x | Kafka client | All services |
| Spring Data Redis | 3.x | Cache, rate limiting | Market Data, Auth, Gateway, Matching Engine |
| Resilience4j | 2.2.x | Circuit breaker | Market Data (Binance calls) |
| Flyway | 10.x | DB migrations | All services with PG |
| Jackson | (managed by Boot) | JSON serialization | — |
| Lombok | Latest | Boilerplate reduction | Optional — remove if clarity preferred |
| Testcontainers | 1.20.x | Integration tests | PG, Kafka, Redis containers in test |
| ArchUnit | 1.x | Architecture enforcement | Verify package dependency rules |

### 4.3 Key Frontend Dependencies

| Dependency | Version | Purpose |
|-----------|---------|---------|
| Next.js | 14+ (App Router) | Framework |
| React | 18+ | UI library |
| TypeScript | 5.x | Type safety |
| TradingView Lightweight Charts | v5 | Candlestick charting |
| Zustand | 4.x | State management |
| TanStack Query (React Query) | 5.x | Server state, caching |
| Tailwind CSS | 3.x | Utility-first styling |

### 4.4 Dependency Rules

- Pin all dependency versions — no `LATEST` or `RELEASE`.
- `exchange-common` must have zero business logic and zero DB dependencies.
- Frontend peer deps: `react >= 18`, `next >= 14` — ensures compatibility when embedded in host app.

---

## 5. API Implementation Guide

### 5.1 Controller Pattern

```java
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final PlaceOrderUseCase placeOrderUseCase;

    @PostMapping
    public ResponseEntity<OrderResponse> placeOrder(
            @RequestHeader("X-User-Id") UUID userId,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PlaceOrderRequest request) {

        Order order = placeOrderUseCase.execute(userId, request.toCommand(), idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(OrderResponse.from(order));
    }
}
```

**Key patterns:**

- `X-User-Id` injected by Gateway after JWT validation — services trust it.
- Idempotency key as optional header.
- `@Valid` on request DTO; basic validation via `jakarta.validation` annotations.
- Business validation (step size, tick size, min notional) in the use case layer.
- Response DTO mapped from domain entity via `from()` static factory.

### 5.2 Request/Response DTOs

```java
public record PlaceOrderRequest(
    @NotBlank String pair,
    @NotNull OrderSide side,
    @NotNull OrderType type,
    @NotNull @DecimalMin("0.00000001") BigDecimal quantity,
    @DecimalMin("0.00000001") BigDecimal price  // required for LIMIT, null for MARKET
) {
    public PlaceOrderCommand toCommand() {
        return new PlaceOrderCommand(pair, side, type, quantity, price);
    }
}
```

**Rules:**

- Use Java records for DTOs.
- Monetary values as `BigDecimal` in Java, serialized as JSON strings.
- Timestamps as `Instant`, serialized as ISO-8601 strings.
- IDs as `UUID`, serialized as strings.

### 5.3 Pagination

Standard cursor-based pagination for list endpoints:

```java
public record PageRequest(
    @Min(1) @Max(100) int size,      // default 20
    UUID cursor,                      // last item ID from previous page (optional)
    SortDirection direction           // ASC or DESC, default DESC
) {}

public record PageResponse<T>(
    List<T> items,
    boolean hasMore,
    UUID nextCursor                   // null if !hasMore
) {}
```

### 5.4 Internal API Conventions

Internal endpoints (called by other services, not by users):

- Path prefix: `/internal/` — explicitly excluded from Gateway routing.
- No JWT validation — network-trust within Docker bridge network.
- Authentication: HMAC signature in `X-Internal-Signature` header for added safety (shared secret from `.env`).

---

## 6. Kafka Event Standards

### 6.1 Event Envelope

Every Kafka message follows a standard envelope:

```java
public record EventEnvelope<T>(
    String eventId,       // UUID — for idempotency dedup
    String eventType,     // e.g., "OrderPlacedEvent"
    int version,          // schema version — starts at 1
    Instant timestamp,
    String source,        // producing service name
    String correlationId, // from MDC
    T payload
) {}
```

### 6.2 Topic Catalog

| Topic | Producer | Consumers | Partition Key |
|-------|----------|-----------|---------------|
| `user.events.v1` | Auth Service | Wallet Service | `userId` |
| `orders.events.v1` | Order Service | Matching Engine, Wallet (logging) | `userId` |
| `matching.events.v1` | Matching Engine | Order Service, Wallet Service | `userId` |
| `wallet.events.v1` | Wallet Service | (audit log only) | `userId` |
| `market-data.events.v1` | Market Data | Matching Engine | `pair` |
| `market-data.depth.v1` | Market Data | Gateway (WS fan-out) | `pair` |
| `market-data.kline.v1` | Market Data | Gateway (WS fan-out) | `pair` |

### 6.3 Consumer Group Naming

Format: `<consuming-service>-<topic>-group`

Example: `matching-engine-orders-events-v1-group`

### 6.4 Outbox Pattern

Every service that publishes events uses the outbox pattern:

1. Domain operation + outbox row insert in same DB transaction.
2. `@Scheduled(fixedDelay = 100)` relay polls `<service>_outbox WHERE published_at IS NULL ORDER BY created_at LIMIT 100`.
3. Publish to Kafka, mark `published_at`.
4. After 10 failed attempts → move to `<service>_outbox_dead_letter` for manual inspection.

---

## 7. Database Migration Strategy

### 7.1 Tool: Flyway

Every service with a database uses Flyway. Migrations run automatically at application startup.

### 7.2 Migration File Naming

```
V1__create_users_table.sql
V2__add_email_verified_column.sql
V3__create_orders_table.sql
```

Convention: `V<number>__<description>.sql` (double underscore). Numbers are sequential per service.

### 7.3 Rules

- Migrations are **append-only**. Never edit an existing migration file after it's been applied.
- Every table change requires a new migration.
- Destructive changes (DROP COLUMN, DROP TABLE) require explicit confirmation in PR review.
- Seed data for reference tables (`Asset`, `TradingPair`, `FeeSchedule`) lives in migrations — same data in dev and prod.
- Use `TIMESTAMPTZ` for all timestamp columns, `UUID` for all ID columns, `NUMERIC(36,18)` for all monetary columns.

---

## 8. Testing Strategy (Overview)

Detailed test plan is a separate document. Key principles here for dev workflow.

### 8.1 Test Pyramid per Service

| Layer | Framework | What's Tested | Target Count |
|-------|-----------|---------------|-------------|
| Unit | JUnit 5 + Mockito | Use cases, domain logic, mappers, validation | 60–80% of tests |
| Integration | Spring Boot Test + Testcontainers | Full request → DB → Kafka flow | 20–30% |
| Architecture | ArchUnit | Package dependency rules, naming | 5–10 rules per service |

### 8.2 Test Commands

```bash
# Unit tests only (fast — no Docker needed)
mvn test -pl services/order-service

# Integration tests (needs Docker — Testcontainers spins up PG/Kafka/Redis)
mvn verify -pl services/order-service

# All tests for all services
mvn verify

# Frontend tests
cd frontend && npm test
```

### 8.3 Key Test Cases to Prioritize

- Wallet: balance invariant (`total = available + frozen`) never violated under concurrent freeze/unfreeze.
- Order: state machine transitions — every valid and invalid transition tested.
- Matching Engine: walk-the-book partial fill with correct VWAP calculation.
- Market Data: Binance WS reconnection doesn't lose/duplicate events.
- Frontend: TradingView chart renders with mock UDF data.

---

## 9. TradingView Integration Summary

### 9.1 Architecture

```
Browser
  └── CandlestickChart.tsx (Lightweight Charts v5)
        ├── [REST] GET /udf/history → Gateway → Market Data Service → TimescaleDB
        └── [WS]  Subscribe kline updates → Gateway WS → Kafka → Market Data Service → Binance WS
```

### 9.2 Key Points

- **Library:** TradingView Lightweight Charts v5 (MIT, ~40KB gzipped). No UDF adapter needed — Lightweight consumes data via method calls (`series.setData()`, `series.update()`).
- **Data source:** The `/udf/*` endpoints are called directly via `fetch` from the `useChartData` hook, NOT via a TV UDF adapter class.
- **Live updates:** WS subscription to kline channel → `series.update()` on each tick.
- **Resolutions supported:** 1m, 5m, 15m, 1h, 4h, 1d.
- **Migration path to Advanced Charting Library (post-MVP):** Replace `createChart` with `new TradingView.widget(...)`, create a UDF DataFeed adapter class, and distribute the Advanced library via `public/` or CDN. The `features/chart/` directory is isolated for this purpose.

### 9.3 Data Feed for VN Stocks (Post-MVP)

The `MarketDataFeedProvider` interface in Market Data Service abstracts the data source. When SSI/TCBS integration is built:

1. Implement `SsiMarketDataProvider` / `TcbsMarketDataProvider`.
2. Activate via `market.data.provider=ssi` in config.
3. No changes needed in frontend or other services — same UDF endpoints, same chart component.

---

## 10. Embeddability Contract (Frontend)

### 10.1 Stage 1 — Standalone

Next.js app runs at `localhost:3000`. Own auth flow. Own routing. Full-page layout.

### 10.2 Stage 2 — Embedded in Host Next.js App

The host app imports `@haizz/trading-panel` as an npm package:

```tsx
import dynamic from 'next/dynamic';

const HaizzTradingPanel = dynamic(
  () => import('@haizz/trading-panel').then(m => m.HaizzTradingPanel),
  { ssr: false, loading: () => <PanelSkeleton /> }
);

export default function TradingPage() {
  return (
    <HaizzTradingPanel
      mode="embedded"
      auth={{ accessToken: hostToken, refreshCallback: hostRefresh }}
      gatewayBaseUrl={process.env.NEXT_PUBLIC_HAIZZ_GATEWAY}
      theme="inherit"
    />
  );
}
```

### 10.3 Key Constraints for All Frontend Code

- No global CSS — all styles scoped with `haizz-` prefix.
- No `window`/`document` access during module evaluation (SSR-safe).
- No global JS singletons — each mount creates its own `QueryClient`, `WsClient`, stores.
- `'use client'` directive at every entry point in `panel/`.

---

## 11. Definition of Done (Per Feature)

A feature is "done" when:

- [ ] Code is committed to a feature branch and self-reviewed.
- [ ] Unit tests pass.
- [ ] Integration tests pass (Testcontainers).
- [ ] No `TODO` or `FIXME` without a linked issue.
- [ ] New REST endpoints are documented in the service README.
- [ ] New/modified Kafka events are reflected in `exchange-common`.
- [ ] The feature works end-to-end in docker-compose (if it spans multiple services).
- [ ] Merged to `main`.
- [ ] Sau khi merge `main`: cập nhật tiến độ — trạng thái SR + project status + todo list — để phản ánh phần vừa hoàn thành và backlog còn lại.

---

## Appendix A: Quick Reference — Ports

| Service | Port |
|---------|------|
| Gateway (API + WS) | 8080 |
| Auth Service | 8081 |
| Wallet Service | 8082 |
| Order Service | 8083 |
| Matching Engine | 8084 |
| Market Data Service | 8085 |
| Frontend | 3000 |
| PostgreSQL (main) | 5432 |
| PostgreSQL (TimescaleDB) | 5433 |
| Redis | 6379 |
| Kafka | 9092 |

## Appendix B: Quick Reference — Kafka Topics

| Topic | Key | Producer → Consumer(s) |
|-------|-----|----------------------|
| `user.events.v1` | userId | Auth → Wallet |
| `orders.events.v1` | userId | Order → Matching, Wallet |
| `matching.events.v1` | userId | Matching → Order, Wallet |
| `wallet.events.v1` | userId | Wallet → (audit) |
| `market-data.events.v1` | pair | Market Data → Matching |
| `market-data.depth.v1` | pair | Market Data → Gateway |
| `market-data.kline.v1` | pair | Market Data → Gateway |

## Appendix C: Quick Reference — Internal Endpoints

| Endpoint | Service | Called By |
|----------|---------|-----------|
| `POST /internal/wallets/freeze` | Wallet | Order |
| `POST /internal/wallets/unfreeze` | Wallet | Order |
| `POST /internal/wallets/settle-trade` | Wallet | (via Kafka — `TradeExecuted`) |
| `GET /internal/ticker/{pair}` | Market Data | Order |
| `GET /internal/depth/{pair}` | Market Data | Matching Engine |
| `GET /internal/pairs/{pair}/metadata` | Market Data | Order |
| `GET /internal/market-data/health` | Market Data | Matching, Order |
| `GET /internal/auth/jwks` | Auth | Gateway |
| `GET /internal/orders?state=OPEN` | Order | Matching Engine (cold start) |

---

*End of `DEV_GUIDE.md`.*
