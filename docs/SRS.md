# Software Requirements Specification (SRS)

**Project Name:** Simulated Crypto Trading Platform (Paper Trading) — *Haizz Exchange*
**Version:** 1.0
**Date:** April 20, 2026
**Author:** Haizz (Product Owner & Developer)
**Status:** Draft — for development guidance
**Related BRD:** Haizz Exchange BRD v1.0 (April 19, 2026)

---

## 1. Introduction

### 1.1 Purpose

This Software Requirements Specification (SRS) translates the business requirements defined in the Haizz Exchange BRD v1.0 into detailed, testable software requirements. It is the authoritative source for:

- Developers (including AI coding agents) implementing individual services.
- Test engineers designing acceptance and integration tests.
- The solo Product Owner reviewing implementation against intent.

This document covers the full MVP scope. Per-service detail — API contracts, state machines, Kafka event schemas, concrete acceptance criteria — lives in five companion appendix documents:

- `SRS_Appendix_WalletService.md`
- `SRS_Appendix_OrderService.md`
- `SRS_Appendix_MatchingEngine.md`
- `SRS_Appendix_MarketDataService.md`
- `SRS_Appendix_UserAuthService.md`

When implementing a service, pair this SRS with the relevant appendix.

### 1.2 Scope

The software product is a simulated spot-trading platform with live price feeds from Binance, virtual (non-real-money) wallets per user, a microservices backend, and an embeddable Next.js frontend.

**In scope (MVP):** User registration, virtual wallet management, simulated USDT deposits, simulated withdrawals, spot trading (market and limit orders) on five crypto pairs, real-time TradingView charting, real-time order book display, maker/taker fee simulation, trade and wallet audit logs, and an embeddable frontend module.

**Out of scope (MVP):** All items listed in BRD Sections 6.2 and Appendix B — notably real money, margin/futures trading, VN equities, advanced order types (stop-loss, OCO), KYC/AML, instructor dashboards, native mobile apps.

### 1.3 Definitions & Acronyms

| Term | Definition |
|------|-----------|
| **Asset** | A tradable unit (e.g., BTC, USDT). |
| **Trading Pair** | A market between two assets (e.g., BTC/USDT — base=BTC, quote=USDT). |
| **Base Asset** | The asset being bought or sold (BTC in BTC/USDT). |
| **Quote Asset** | The asset used to price the base (USDT in BTC/USDT). |
| **Market Order** | An order that executes immediately at the current best available price. |
| **Limit Order** | An order that rests until the market reaches a specified price. |
| **GTC** | Good-Till-Cancelled — a limit order remains active until filled or cancelled. |
| **IOC / FOK** | Immediate-Or-Cancel / Fill-Or-Kill — deferred to post-MVP (BR-020). |
| **Maker** | An order that adds liquidity to the book (a resting limit order that gets filled). |
| **Taker** | An order that removes liquidity (a market order, or a limit that crosses the spread). |
| **OHLCV** | Open, High, Low, Close, Volume — the five data points of a candlestick. |
| **Order Book** | The set of currently resting bids and asks for a pair. |
| **Spread** | The gap between best bid and best ask. |
| **Slippage** | The difference between expected fill price and actual fill price. |
| **VWAP** | Volume-Weighted Average Price — average fill price across multiple price levels. |
| **Walk-the-book** | Filling a large market order across multiple price levels. |
| **Tick Size** | The minimum price increment for a pair. |
| **Step Size** | The minimum quantity increment for a pair. |
| **Min Notional** | The minimum order value (price × quantity) allowed for a pair. |
| **Frozen Balance** | Wallet balance reserved by open orders; unavailable for new orders or withdrawal. |
| **Available Balance** | Total wallet balance minus frozen; usable for new orders and withdrawals. |
| **Saga** | A distributed transaction pattern using local transactions + compensating actions. |
| **Idempotency Key** | A client-supplied identifier that makes a repeated request safe (no duplicate effect). |
| **Stage 1 / Stage 2** | Standalone POC (Stage 1); embedded inside host education platform (Stage 2). |
| **Host App** | The existing education platform that will embed the trading module in Stage 2. |

### 1.4 Traceability to BRD

Every SRS functional requirement (SR-xxx) traces to one or more BRD requirements (BR-xxx). Full traceability matrix in Section 10.

---

## 2. System Overview

### 2.1 System Context

Haizz Exchange sits between three external entities and its end users:

- **Upstream data providers** — Binance public REST and WebSocket APIs (crypto market data). SSI/TCBS deferred to post-MVP.
- **Downstream host application** — the existing education platform, which will embed the frontend in Stage 2 via an iframe or module mount. The host provides SSO in Stage 2.
- **End users (learners and instructors)** — interact via web browser, either standalone (Stage 1) or from within the host app (Stage 2).

Internally the system is a set of microservices communicating via Kafka (async) and HTTP (sync, time-sensitive), with PostgreSQL as source of truth per service, Redis for caching and short-term state, and TimescaleDB for time-series OHLCV data.

### 2.2 Actors

| Actor | Description | Key Capabilities |
|-------|-------------|-----------------|
| **Learner** | The primary end user. Signs up, practices trading, manages virtual portfolio. | Register, log in, deposit/withdraw (simulated), view wallets, place/cancel orders, view charts, view order book, view trade history. |
| **Instructor** (post-MVP) | Secondary user. Uses the platform as a teaching aid. | In MVP: has no special privileges beyond Learner. Post-MVP: view learner activity, assign exercises. |
| **System (Matching Engine)** | Internal actor. Consumes external market data and simulates order matching. | Subscribe to Binance WebSocket, evaluate open orders against live prices, emit trade events. |
| **System (Market Data Ingestor)** | Internal actor. Fetches and distributes external market data. | Connect to Binance REST and WebSocket, cache in Redis, persist OHLCV to TimescaleDB, republish on internal topics. |
| **External Price Feed (Binance)** | External actor. Source of truth for market prices. | Provides REST endpoints (`/api/v3/klines`, `/api/v3/exchangeInfo`, `/api/v3/depth`) and WebSocket streams (`@kline`, `@trade`, `@depth`, `@bookTicker`). |
| **Host Application (Stage 2)** | External actor. Embeds the trading frontend. | Issues SSO tokens, hosts the iframe/module, relays identity. |

### 2.3 System Boundaries

The following diagram describes system boundaries at a conceptual level.

```
                    ┌──────────────────────────────────┐
                    │       External (Binance)         │
                    │  REST APIs  •  WebSocket Streams │
                    └─────────────┬────────────────────┘
                                  │ (ingests)
                    ┌─────────────▼────────────────────┐
                    │       Haizz Exchange (MVP)       │
                    │                                  │
                    │  ┌────────────────────────────┐  │
                    │  │ API Gateway / WS Gateway   │  │
                    │  └──────────┬─────────────────┘  │
                    │             │                    │
                    │  ┌──────────┴───────────────┐    │
                    │  │ User/Auth • Order •      │    │
                    │  │ Matching • Wallet •      │    │
                    │  │ Market Data              │    │
                    │  │ (Kafka bus between them) │    │
                    │  └──────────────────────────┘    │
                    │                                  │
                    └─────────────┬────────────────────┘
                                  │ (serves)
                    ┌─────────────▼────────────────────┐
                    │  Frontend (Next.js)              │
                    │  Standalone (Stage 1) OR         │
                    │  Embedded in Host App (Stage 2)  │
                    └──────────────────────────────────┘
```

---

## 3. Functional Requirements

Requirements are grouped by feature area. Each has a unique ID, a priority (Must/Should/Could per BRD MoSCoW), the responsible service, and acceptance criteria in Given/When/Then form. Full per-service acceptance criteria with edge cases live in the service appendices.

### 3.1 User Management & Authentication

Responsible service: **User & Auth Service**. See `SRS_Appendix_UserAuthService.md` for full API contracts and token flows.

| ID | Requirement | Actor | Priority | Traces to |
|----|------------|-------|----------|-----------|
| SR-001 | The system shall allow a new user to register with email and password. | Learner | Must | BR-001 |
| SR-002 | The system shall reject registration if the email is already in use. | Learner | Must | BR-001 |
| SR-003 | The system shall hash all passwords using bcrypt or argon2 before persistence. | System | Must | NFR-008 |
| SR-004 | The system shall authenticate a registered user via email and password and issue a JWT access token. | Learner | Must | BR-001, NFR-008 |
| SR-005 | The system shall enforce a JWT access token expiry of 60 minutes and refresh token expiry of 7 days. | System | Must | NFR-008 |
| SR-006 | The system shall allow the Auth service to delegate authentication to an external OAuth2/OIDC provider (host app) in Stage 2 without changes to other services. | Learner (Stage 2) | Should | BR-016 |
| SR-007 | The system shall log out a user by invalidating their refresh token. | Learner | Must | BR-001, NFR-008 |

**US-001 (Registration):** As a new learner, I want to register with an email and password so that I can start practicing trading.

**Acceptance Criteria:**
- Given the user provides a valid, unused email and a password meeting policy (≥ 8 chars, 1 letter, 1 digit), when they submit the registration form, then a new `User` record is created, a `Wallet` record is created for each supported asset (initially zero balance except USDT = 10,000), a `WalletTransaction` of type `SIGNUP_GRANT` is written for the USDT credit, and the user is returned a JWT access token and refresh token.
- Given the user provides an email already in use, when they submit registration, then the system returns HTTP 409 Conflict with error code `EMAIL_IN_USE` and no records are created.
- Given the user provides a password not meeting policy, when they submit registration, then the system returns HTTP 400 with error code `PASSWORD_POLICY_VIOLATION` and the specific rule violated.

**US-002 (Login):** As a registered learner, I want to log in so that I can access my portfolio and place orders.

**Acceptance Criteria:**
- Given the user provides correct email and password, when they submit login, then the system returns a JWT access token (60 min) and refresh token (7 days).
- Given the user provides incorrect credentials, when they submit login, then the system returns HTTP 401 with error code `INVALID_CREDENTIALS`. The response time must be constant-ish (within ±50 ms) to prevent timing attacks on email existence.
- Given a user submits 10 failed login attempts within 10 minutes for the same email, when they submit the 11th attempt, then the system returns HTTP 429 with error code `TOO_MANY_ATTEMPTS` and the account is rate-limited for 15 minutes.

### 3.2 Wallet Management

Responsible service: **Wallet Service**. See `SRS_Appendix_WalletService.md` for full detail.

| ID | Requirement | Actor | Priority | Traces to |
|----|------------|-------|----------|-----------|
| SR-010 | The system shall create one `Wallet` per user per asset on user registration. | System | Must | BR-002 |
| SR-011 | The system shall credit each new user 10,000 USDT on registration as initial virtual balance. | System | Must | BR-002 |
| SR-012 | The system shall track three balance values per wallet: `total`, `available`, `frozen`, where `total = available + frozen` at all times. | System | Must | BR-009, NFR-005 |
| SR-013 | The system shall allow a user to submit a simulated deposit of USDT up to 100,000 USDT per transaction. | Learner | Must | BR-003 |
| SR-014 | The system shall confirm simulated deposits instantly (no artificial delay). | System | Must | BR-003 |
| SR-015 | The system shall reject simulated deposits of any asset other than USDT in MVP. | System | Must | BR-003 |
| SR-016 | The system shall allow a user to withdraw any asset up to their `available` balance of that asset. | Learner | Must | BR-003 |
| SR-017 | The system shall reject a withdrawal if the requested amount exceeds the `available` balance; the user must cancel open orders first to release frozen balance. | Learner | Must | BR-003, NFR-005 |
| SR-018 | The system shall confirm simulated withdrawals instantly (no artificial delay, no 2FA simulation). | System | Must | BR-003 |
| SR-019 | The system shall record every balance change as an immutable `WalletTransaction` with type, amount, asset, before/after balance, reference ID (order/trade/deposit/withdrawal), and UTC timestamp. | System | Must | BR-010, NFR-007 |
| SR-020 | The system shall guarantee that no wallet operation can result in a negative `available` balance. | System | Must | NFR-005 |
| SR-021 | The system shall expose a read endpoint returning all wallets for the authenticated user with total/available/frozen per asset. | Learner | Must | BR-009 |
| SR-022 | The system shall expose a read endpoint returning the authenticated user's wallet transaction history, paginated, sortable by timestamp. | Learner | Must | BR-010 |
| SR-023 | The system shall handle concurrent balance mutations for the same wallet using optimistic locking with pessimistic-lock fallback on retry, never permitting a lost update. | System | Must | NFR-005 |

**US-010 (View Wallets):** As a learner, I want to see my balance for each asset so that I know what I can trade with.

**Acceptance Criteria:**
- Given the user is authenticated, when they GET `/api/v1/wallets/me`, then the response lists every wallet they own with `assetCode`, `total`, `available`, `frozen`, and `updatedAt`.
- Given the user has a wallet with total=1.5 BTC and an open limit sell of 0.5 BTC, then the response shows BTC with total=1.5, available=1.0, frozen=0.5.

**US-011 (Deposit USDT):** As a learner, I want to deposit additional simulated USDT so that I can try different position sizes.

**Acceptance Criteria:**
- Given the user requests a deposit of 50,000 USDT, when the request is accepted, then the user's USDT `available` and `total` both increase by 50,000, a `WalletTransaction` of type `DEPOSIT` is created, a `DepositRecord` of status `CONFIRMED` is created, and the response returns HTTP 200 with the new balance.
- Given the user requests a deposit of 150,000 USDT, when the request is evaluated, then the system returns HTTP 400 with error code `DEPOSIT_AMOUNT_EXCEEDS_LIMIT` (limit: 100,000 USDT/tx) and no balance changes.
- Given the user requests a deposit of BTC, when the request is evaluated, then the system returns HTTP 400 with error code `DEPOSIT_ASSET_NOT_SUPPORTED` and no balance changes.

**US-012 (Withdraw):** As a learner, I want to withdraw simulated funds so that I can practice capital management.

**Acceptance Criteria:**
- Given the user has available=10,000 USDT and frozen=60,000 USDT, when they request withdrawal of 8,000 USDT, then USDT `available` and `total` both decrease by 8,000, a `WalletTransaction` of type `WITHDRAWAL` is created, a `WithdrawalRecord` of status `CONFIRMED` is created, and HTTP 200 is returned.
- Given the user has available=10,000 USDT and frozen=60,000 USDT, when they request withdrawal of 20,000 USDT, then the system returns HTTP 400 with error code `INSUFFICIENT_AVAILABLE_BALANCE`, including the current available amount in the response, and no balance changes occur.

### 3.3 Order Management

Responsible service: **Order Service**. See `SRS_Appendix_OrderService.md` for full detail including state machine and event contracts.

| ID | Requirement | Actor | Priority | Traces to |
|----|------------|-------|----------|-----------|
| SR-030 | The system shall accept market buy/sell orders specifying `pair` and `quantity` (base asset qty) or `quoteOrderQty` (notional in quote asset). | Learner | Must | BR-004 |
| SR-031 | The system shall accept limit buy/sell orders specifying `pair`, `price`, and `quantity`. | Learner | Must | BR-005 |
| SR-032 | The system shall support Time-In-Force = GTC for limit orders in MVP. IOC and FOK are deferred (BR-020). | Learner | Must | BR-005, BR-020 |
| SR-033 | The system shall validate every order against the pair's `minNotional`, `tickSize`, and `stepSize` before acceptance. | System | Must | BR-004, BR-005 |
| SR-034 | The system shall reject an order if the user's `available` balance of the required asset is insufficient (quote asset for buy, base asset for sell). | System | Must | NFR-005 |
| SR-035 | The system shall freeze the required balance at order placement and release it on cancellation or upon each fill (in proportion to filled quantity). | System | Must | NFR-005 |
| SR-036 | The system shall generate a unique `orderId` (UUID v4) for every accepted order. | System | Must | BR-004, BR-005 |
| SR-037 | The system shall accept a client-supplied `clientOrderId` (idempotency key) and reject duplicates submitted within 60 seconds with HTTP 409. | Learner | Must | NFR-005 |
| SR-038 | The system shall allow a user to cancel any of their own orders in state `NEW` or `PARTIALLY_FILLED`. | Learner | Must | BR-006 |
| SR-039 | The system shall reject cancellation of orders in state `FILLED`, `CANCELLED`, `REJECTED`, or `EXPIRED` with HTTP 409 and error code `ORDER_NOT_CANCELLABLE`. | Learner | Must | BR-006 |
| SR-040 | The system shall emit a Kafka event `OrderPlaced` on successful order acceptance and `OrderCancelled` on successful cancellation. | System | Must | BR-004, BR-005, BR-006 |
| SR-041 | The system shall expose a read endpoint returning the authenticated user's orders filterable by status, pair, and date range. | Learner | Must | BR-007 |
| SR-042 | The system shall acknowledge order placement to the client within 500 ms at the 95th percentile. | System | Must | NFR-001 |

**US-020 (Place Market Buy):** As a learner, I want to submit a market buy order so that I can get immediate exposure to an asset.

**Acceptance Criteria:**
- Given the user has USDT available ≥ estimated frozen amount, when they submit a market buy for 0.1 BTC on BTC/USDT with current best ask = 60,000, then the system accepts the order, freezes `60000 × 0.1 × 1.0005 × 1.01 ≈ 6,063.03 USDT` (best ask × qty × (1 + slippage) × safety buffer for walk-the-book overshoot), creates an `Order` record with state `NEW` and type `MARKET`, publishes `OrderPlaced`, and returns HTTP 201 with the `orderId` within 500 ms.
- Given the user has insufficient USDT for the frozen amount, when they submit a market buy, then the system returns HTTP 400 with error code `INSUFFICIENT_BALANCE` and no order is created.
- Given the user submits a market buy with `quantity` below the pair's `minNotional` (e.g., 0.00001 BTC when min is ~10 USDT), when the order is validated, then the system returns HTTP 400 with error code `MIN_NOTIONAL_VIOLATION`.

**US-021 (Place Limit Buy):** As a learner, I want to place a limit buy order so that I can buy at a target price.

**Acceptance Criteria:**
- Given the user has USDT available ≥ price × quantity, when they submit a limit buy of 1 BTC at 60,000 USDT, then the system freezes exactly 60,000 USDT (price × qty; fee is paid from received base asset at fill), creates an `Order` in state `NEW` type `LIMIT` TIF `GTC`, publishes `OrderPlaced`, and returns HTTP 201.
- Given the user submits a limit price with precision finer than the pair's `tickSize` (e.g., 60000.123 when tick is 0.01), when validation runs, then the system returns HTTP 400 with error code `TICK_SIZE_VIOLATION`.

**US-022 (Cancel Order):** As a learner, I want to cancel an open order so that I can change my strategy.

**Acceptance Criteria:**
- Given an order is in state `NEW`, when the user POSTs `/api/v1/orders/{orderId}/cancel`, then the order transitions to `CANCELLED`, any unfilled frozen balance is released to `available`, an `OrderCancelled` event is published, and HTTP 200 is returned.
- Given an order is in state `PARTIALLY_FILLED` with 30% filled, when the user cancels it, then the order transitions to `CANCELLED`, the remaining 70% of frozen balance is released, and filled portion's effects on balance (via `TradeExecuted`) are preserved.
- Given an order is already `FILLED`, when the user attempts to cancel, then the system returns HTTP 409 with error code `ORDER_NOT_CANCELLABLE`.

### 3.4 Order Matching (Simulation)

Responsible service: **Matching Engine**. See `SRS_Appendix_MatchingEngine.md` for full simulation algorithm.

| ID | Requirement | Actor | Priority | Traces to |
|----|------------|-------|----------|-----------|
| SR-050 | The system shall simulate order matching against live external Binance market data — no peer-to-peer matching between platform users in MVP. | System | Must | BR-004, BR-005 (matching note in BRD §8) |
| SR-051 | For market orders, the system shall fill at Binance best bid/ask adjusted by a fixed slippage of 0.05% (taker side: buy at ask×1.0005, sell at bid×0.9995). | System | Must | BR-004 |
| SR-052 | For market orders exceeding the top-of-book depth, the system shall walk the Binance depth snapshot, consuming price levels until the full quantity is filled, and compute a volume-weighted average fill price. | System | Must | BR-004 |
| SR-053 | For limit orders, the system shall mark the order as eligible to fill when the external last-traded price touches the limit price (`lastPrice ≤ limitPrice` for buy, `lastPrice ≥ limitPrice` for sell). | System | Must | BR-005 |
| SR-054 | For limit orders, the system shall partially fill the order based on the volume of external trades that occur at eligible prices, with a maximum buffer window of 5 seconds per fill evaluation cycle. | System | Must | BR-005 |
| SR-055 | The system shall create a `Trade` record for every fill event (full or partial) containing `tradeId`, `orderId`, `userId`, `pair`, `side`, `price`, `quantity`, `feeAmount`, `feeAsset`, `makerOrTaker`, and `executedAt`. | System | Must | BR-008 |
| SR-056 | The system shall publish a `TradeExecuted` Kafka event for every trade, consumed by Wallet Service (for balance update) and Order Service (for order state update). | System | Must | BR-008, NFR-005 |
| SR-057 | The system shall compute fees as: taker market order pays 0.10% taker fee; limit order filled as maker pays 0.10% maker fee. | System | Must | BR-013 |
| SR-058 | The system shall deduct fee in the base asset for buy orders and in the quote asset for sell orders (Binance convention). | System | Must | BR-013 |
| SR-059 | If the Binance price feed is unavailable for longer than 30 seconds, the system shall pause matching for affected pairs and reject new market orders with error code `PRICE_FEED_UNAVAILABLE`. Existing limit orders remain open. | System | Must | NFR-013 |

**US-030 (Market Order Fill — Walk the Book):** As a learner, I want a market order larger than top depth to fill across multiple price levels so that I understand real execution mechanics.

**Acceptance Criteria:**
- Given the current Binance BTC/USDT depth snapshot is `[{60000: 0.5}, {60001: 0.3}, {60002: 2.0}]` on the ask side, when the user submits a market buy of 1 BTC, then Matching Engine fills: 0.5 BTC @ 60000 + 0.3 BTC @ 60001 + 0.2 BTC @ 60002, VWAP = (0.5×60000 + 0.3×60001 + 0.2×60002) / 1 = 60000.7 USDT/BTC, then applies the 0.05% slippage penalty → final fill price = 60000.7 × 1.0005 ≈ 60030.7 USDT.
- The user is debited 60030.7 USDT (the actual trade cost from frozen). Any remaining frozen USDT beyond this cost (the safety buffer from BRL-006) is released back to `available`.
- Fee is computed as 0.1% of 1 BTC = 0.001 BTC (taker fee, paid in received base asset). User's BTC wallet is credited 0.999 BTC (1 BTC - 0.001 BTC fee).
- A single `Trade` record is created with `price=60030.7`, `quantity=1`, `feeAmount=0.001`, `feeAsset=BTC`, `role=TAKER` (multi-leg trade tracking is deferred to post-MVP).

**US-031 (Limit Order Partial Fill):** As a learner, I want my limit order to partially fill based on real market volume so that I see realistic fill behavior.

**Acceptance Criteria:**
- Given an open limit buy for 2 BTC at 60,000 USDT, and the external market trades 0.3 BTC at 59,999 USDT, when Matching Engine evaluates the tick, then a partial fill of 0.3 BTC at the limit price 60,000 is executed, a `Trade` record is created, the order state transitions `NEW` → `PARTIALLY_FILLED`, `filledQuantity` becomes 0.3, and the remaining 1.7 BTC stays resting.
- Given the same order, when external trade volume at eligible price accumulates to 2 BTC total across multiple ticks, then the order transitions `PARTIALLY_FILLED` → `FILLED`.

### 3.5 Market Data

Responsible service: **Market Data Service**. See `SRS_Appendix_MarketDataService.md` for full endpoint and stream specification.

| ID | Requirement | Actor | Priority | Traces to |
|----|------------|-------|----------|-----------|
| SR-060 | The system shall ingest historical candlestick (OHLCV) data from Binance REST `/api/v3/klines` for the five MVP pairs at 1m, 5m, 15m, 1h, 4h, 1d resolutions. | System | Must | BR-011, BR-014 |
| SR-061 | The system shall persist OHLCV data in TimescaleDB as a hypertable partitioned by `openTime`. | System | Must | BR-011 |
| SR-062 | The system shall subscribe to Binance WebSocket streams `@kline_1m`, `@trade`, and `@depth@100ms` for each MVP pair and publish the data on internal Kafka topics `market.kline.{pair}`, `market.trade.{pair}`, `market.depth.{pair}`. | System | Must | BR-011, BR-012, BR-014 |
| SR-063 | The system shall expose REST endpoints conforming to the TradingView UDF specification (`/udf/config`, `/udf/symbols`, `/udf/history`) for chart integration. | System | Must | BR-011 |
| SR-064 | The system shall expose a REST endpoint `/api/v1/marketdata/orderbook/{pair}?depth=20` returning the top N depth levels from cache, with price and aggregated quantity. | Learner (via UI) | Must | BR-012 |
| SR-065 | The system shall fetch Binance `/api/v3/exchangeInfo` at service startup and cache pair metadata (tickSize, stepSize, baseAsset, quoteAsset) in Redis for lookup by Order Service. | System | Must | BR-004, BR-005 |
| SR-066 | The system shall expose a REST endpoint `/api/v1/marketdata/exchangeInfo` that returns the cached pair metadata for the 5 MVP pairs. | System | Must | BR-004, BR-005, BR-017 |
| SR-067 | The system shall propagate a new candlestick update to connected clients within 2 seconds of its receipt from Binance at the 95th percentile. | System | Must | NFR-002 |
| SR-068 | On Binance WebSocket disconnect, the system shall attempt reconnection with exponential backoff (1s, 2s, 4s, 8s, max 30s) and log each reconnect attempt. | System | Must | NFR-013 |
| SR-069 | The system shall cache the current ticker (last price, best bid, best ask) per pair in Redis with TTL 10 seconds, refreshed on each WebSocket tick. | System | Must | BR-012, BR-014 |

**US-040 (View Live Chart):** As a learner, I want to see real-time candlestick charts for a pair so that I can analyze price action.

**Acceptance Criteria:**
- Given the user opens the trading UI for BTC/USDT, when the TradingView chart requests history via `/udf/history?symbol=BTCUSDT&resolution=1&from=...&to=...`, then the Market Data Service returns OHLCV in UDF JSON format within 1 second.
- Given the chart is subscribed to live updates, when a new 1-minute candle closes on Binance, then the update is visible on the user's chart within 2 seconds (95th percentile).

**US-041 (View Order Book):** As a learner, I want to see the current order book for a pair so that I can judge liquidity.

**Acceptance Criteria:**
- Given the user opens the order book widget for BTC/USDT, when they request depth 20, then the response contains up to 20 bid levels and 20 ask levels, each with price and aggregated quantity, sourced from the cached Binance depth snapshot (< 1s old).
- Given the Binance depth stream is disconnected, when the user requests the order book, then the response includes a `stale: true` flag and the last known snapshot.

### 3.6 Frontend (Embeddable Next.js Module)

Responsible module: **Frontend (Next.js app)**. This section covers UI-level functional requirements. Visual and interaction design live in a separate design document.

| ID | Requirement | Actor | Priority | Traces to |
|----|------------|-------|----------|-----------|
| SR-070 | The frontend shall be implemented as a Next.js application using React 18+. | System | Must | BR-015 |
| SR-071 | The frontend shall expose a single-mount entry point that allows the host app to embed it via a dynamic import or iframe without global-style collisions. | System | Must | BR-015, NFR-011 |
| SR-072 | The frontend shall integrate TradingView Lightweight Charts v5 for candlestick rendering. | System | Must | BR-011 |
| SR-073 | The frontend shall provide screens: Login/Register, Wallet Overview, Trade (pair selector + chart + order book + order form + open orders), Order History, Trade History, Deposit/Withdraw. | Learner | Must | BR-001 through BR-013 |
| SR-074 | The frontend shall subscribe to WebSocket streams from the API Gateway for: price ticks, order updates, and wallet balance updates for the authenticated user. | Learner | Must | BR-011, BR-012, BR-014 |
| SR-075 | The frontend shall accept an external auth token (JWT) from the host app context (Stage 2) in lieu of its own login flow. | Learner (Stage 2) | Should | BR-015, BR-016 |
| SR-076 | The frontend shall be responsive for viewports ≥ 1024 px wide. Mobile-specific layouts are deferred. | Learner | Must | BR-015 |

### 3.7 API Gateway & WebSocket Gateway

| ID | Requirement | Actor | Priority | Traces to |
|----|------------|-------|----------|-----------|
| SR-080 | The system shall expose a single API Gateway (Spring Cloud Gateway) that routes REST requests to the appropriate backend service based on path prefix. | System | Must | NFR-010 |
| SR-081 | The API Gateway shall validate the JWT on every request requiring authentication and inject `userId` and `roles` claims as downstream headers. | System | Must | NFR-008 |
| SR-082 | The API Gateway shall enforce per-user rate limits: 60 requests/second sustained, 120 burst, returning HTTP 429 on violation. | System | Must | NFR-008 |
| SR-083 | The system shall expose a WebSocket Gateway handling multiplexed subscriptions for price, order, and wallet streams, with one connection per authenticated user. | System | Must | BR-011, BR-012, BR-014 |
| SR-084 | The WebSocket Gateway shall authenticate the connection via JWT provided at handshake and terminate the connection on token expiry. | System | Must | NFR-008 |

---

## 4. Data Requirements

### 4.1 Core Data Entities

Cross-service entity ownership follows DDD bounded-context principles. An entity is "owned" by the service responsible for its lifecycle. Other services hold only the identifiers or cached read-models (propagated via Kafka).

| Entity | Description | Key Attributes | Owner Service |
|--------|-------------|---------------|---------------|
| **User** | A registered learner. | `userId` (UUID), `email`, `passwordHash`, `status`, `createdAt` | User & Auth |
| **Asset** | A tradable asset (catalog). | `assetCode` (e.g., BTC, USDT), `name`, `decimals`, `isActive` | Order Service (shared reference for MVP) |
| **TradingPair** | A market between two assets. | `pairSymbol` (e.g., BTCUSDT), `baseAsset`, `quoteAsset`, `tickSize`, `stepSize`, `minNotional`, `isActive` | Order Service (shared reference for MVP) |
| **Wallet** | A user's balance for one asset. | `walletId`, `userId`, `assetCode`, `totalBalance`, `availableBalance`, `frozenBalance`, `version` (optimistic lock), `updatedAt` | Wallet Service |
| **WalletTransaction** | Immutable audit record of a balance change. | `txnId`, `walletId`, `type` (SIGNUP_GRANT/DEPOSIT/WITHDRAWAL/FREEZE/UNFREEZE/TRADE_CREDIT/TRADE_DEBIT/FEE), `amount`, `balanceBefore`, `balanceAfter`, `referenceId`, `createdAt` | Wallet Service |
| **DepositRecord** | A simulated deposit request. | `depositId`, `userId`, `assetCode`, `amount`, `status` (PENDING/CONFIRMED/FAILED), `createdAt`, `confirmedAt` | Wallet Service |
| **WithdrawalRecord** | A simulated withdrawal request. | `withdrawalId`, `userId`, `assetCode`, `amount`, `status`, `createdAt`, `confirmedAt` | Wallet Service |
| **Order** | An order placed by a user. | `orderId`, `clientOrderId`, `userId`, `pairSymbol`, `side` (BUY/SELL), `type` (MARKET/LIMIT), `price`, `quantity`, `filledQuantity`, `status`, `timeInForce`, `createdAt`, `updatedAt` | Order Service |
| **Trade** | A fill event (full or partial). | `tradeId`, `orderId`, `userId`, `pairSymbol`, `side`, `price`, `quantity`, `quoteQuantity`, `feeAmount`, `feeAsset`, `role` (MAKER/TAKER), `executedAt` | Matching Engine |
| **Candlestick** | OHLCV bar for a pair at a resolution. | `pairSymbol`, `resolution` (1m/5m/...), `openTime`, `open`, `high`, `low`, `close`, `volume`, `closeTime` | Market Data Service |
| **FeeSchedule** | Fee tier configuration. | `tierId`, `makerRate`, `takerRate`, `effectiveFrom` | Matching Engine (MVP: single hard-coded tier) |

### 4.2 Key Business Rules

| ID | Rule | Applies To | Example |
|----|------|-----------|---------|
| BRL-001 | Wallet invariant: `totalBalance = availableBalance + frozenBalance` at the end of every transaction. | Wallet | Before freeze: total=10,000, available=10,000, frozen=0. After freezing 6,000 for a limit buy: total=10,000, available=4,000, frozen=6,000. |
| BRL-002 | Initial USDT grant on registration is 10,000 USDT; no other asset is pre-funded. | Wallet | Registration creates: USDT wallet with 10,000/10,000/0; BTC wallet with 0/0/0; etc. |
| BRL-003 | Deposit in MVP is USDT-only, max 100,000 USDT per transaction. | Wallet | User requests deposit 50,000 USDT → accepted. User requests 150,000 USDT → rejected (`DEPOSIT_AMOUNT_EXCEEDS_LIMIT`). User requests 1 BTC → rejected (`DEPOSIT_ASSET_NOT_SUPPORTED`). |
| BRL-004 | Withdrawal cannot exceed `availableBalance`; user must cancel open orders to free frozen balance. | Wallet | User has available=10,000 USDT, frozen=60,000 USDT, requests withdraw 20,000 → rejected. User must first cancel the limit order holding the 60,000 freeze. |
| BRL-005 | Order validation order: (1) pair exists and is active; (2) quantity/price respects `stepSize`/`tickSize`; (3) notional ≥ `minNotional`; (4) sufficient available balance. On first failure, reject with specific error code. | Order | A limit buy with price=60,000.123 fails at step (2) with `TICK_SIZE_VIOLATION` even if balance is sufficient. |
| BRL-006 | Freeze rules: Limit BUY freezes exactly `price × quantity` of the quote asset (no fee reserve — fee is paid in base asset at fill). Limit SELL freezes exactly `quantity` of the base asset (no fee reserve — fee is paid in quote asset at fill). Market BUY freezes `currentBestAsk × quantity × (1 + slippage) × (1 + safetyBuffer)` of the quote asset, where `slippage = 0.0005` and `safetyBuffer = 0.01` to cover walk-the-book overshoot. Market SELL freezes exactly `quantity` of the base asset. | Order/Wallet | Limit buy 1 BTC @ 60,000 USDT → freeze 60,000 USDT (flat, no fee reserve). Limit sell 1 BTC @ 60,000 USDT → freeze 1.0 BTC. Market buy 1 BTC with best ask 60,000 → freeze ~60,630 USDT (60,000 × 1.0005 × 1.01). |
| BRL-007 | On fill, the quote/base asset consumed by the trade is debited from frozen. Fees are deducted from the *received* asset implicitly: buyer receives (filledQty - feeInBase) of base; seller receives (quoteValue - feeInQuote) of quote. For market orders, any unused freeze (safety buffer remainder) is released back to available after the fill. | Order/Wallet | Limit buy 1 BTC fills at 60,000: frozen 60,000 USDT → 0 (fully spent). BTC wallet +0.999 (received 1 BTC minus 0.001 BTC fee). Market buy 1 BTC frozen 60,630, actual fill cost 60,030 → 60,030 debited, 600 released to available. |
| BRL-008 | Maker fee applies to limit order fills (in simulation, all limit fills are treated as maker per MVP matching model). Taker fee applies to market order fills. The fee currency is the asset received: buy orders pay fee in base asset (reduces received base); sell orders pay fee in quote asset (reduces received quote). | Matching Engine | 1 BTC limit buy at 60,000 fills → 0.1% maker fee = 0.001 BTC deducted from received, user receives 0.999 BTC. 1 BTC market sell at 60,000 → 0.1% taker fee = 60 USDT deducted, user receives 59,940 USDT. |
| BRL-009 | Limit order fill trigger: external last-traded price touches or crosses the limit (`lastPrice ≤ limit` for buy, `lastPrice ≥ limit` for sell). | Matching Engine | Limit buy @ 60,000: external trade at 60,000 → eligible; external trade at 59,999 → eligible; external trade at 60,001 → not eligible. |
| BRL-010 | Market order with quantity exceeding top-level depth walks the Binance depth snapshot, computing VWAP across consumed levels, then applies 0.05% slippage on top of VWAP. | Matching Engine | See US-030 acceptance criteria. |
| BRL-011 | Fee deduction asset: buy orders pay fee in base asset (received asset is reduced); sell orders pay fee in quote asset (received asset is reduced). | Matching Engine | Buy 1 BTC with 0.1% fee → receive 0.999 BTC. Sell 1 BTC for 60,000 USDT with 0.1% fee → receive 59,940 USDT. |
| BRL-012 | Order state machine: `NEW → PARTIALLY_FILLED → FILLED` (happy path); `NEW → CANCELLED`; `PARTIALLY_FILLED → CANCELLED`; `NEW → REJECTED` (only at acceptance time). No transitions from terminal states (`FILLED`, `CANCELLED`, `REJECTED`, `EXPIRED`). | Order | See state diagram in Appendix `SRS_Appendix_OrderService.md`. |
| BRL-013 | Idempotency: a `clientOrderId` submitted twice within 60 seconds returns the result of the first request (not a duplicate order). After 60 seconds, the same `clientOrderId` may be reused. | Order | User retries a timed-out place-order with same `clientOrderId` → gets the first attempt's `orderId`. |
| BRL-014 | All monetary amounts are stored as `DECIMAL(36, 18)` in the database to avoid floating-point rounding errors. Calculations use `BigDecimal` in Java with `HALF_UP` rounding and per-pair precision from `tickSize`/`stepSize`. | All services | Price 60000.01 is stored precisely; multiplication 0.1 × 60000.01 = 6000.001 stored precisely; display is rounded to pair precision. |

### 4.3 Data Validation Rules

| Field | Validation | Error Message / Code |
|-------|-----------|---------------------|
| `email` | RFC 5322 format; lowercase on persist; max 254 chars. | `INVALID_EMAIL_FORMAT` / "Please provide a valid email address." |
| `password` | ≥ 8 chars; ≥ 1 letter; ≥ 1 digit. | `PASSWORD_POLICY_VIOLATION` / "Password must be at least 8 characters and include a letter and a digit." |
| `pairSymbol` | Must exist in active TradingPair list. | `INVALID_PAIR` / "Trading pair is not supported." |
| `order.quantity` | > 0; conforms to `stepSize` of pair. | `STEP_SIZE_VIOLATION` / "Quantity must be a multiple of {stepSize}." |
| `order.price` (limit) | > 0; conforms to `tickSize` of pair. | `TICK_SIZE_VIOLATION` / "Price must be a multiple of {tickSize}." |
| `order.notional` (price × qty) | ≥ `minNotional` of pair. | `MIN_NOTIONAL_VIOLATION` / "Order value must be at least {minNotional} {quoteAsset}." |
| `deposit.amount` | > 0; ≤ 100,000 USDT. | `DEPOSIT_AMOUNT_EXCEEDS_LIMIT` / "Maximum deposit per transaction is 100,000 USDT." |
| `deposit.assetCode` | = "USDT" in MVP. | `DEPOSIT_ASSET_NOT_SUPPORTED` / "Only USDT deposits are supported in this release." |
| `withdrawal.amount` | > 0; ≤ current available balance of that asset. | `INSUFFICIENT_AVAILABLE_BALANCE` / "Insufficient available balance. Cancel open orders to free frozen balance." |
| `clientOrderId` | UUID v4 format; unique within user + 60s window. | `DUPLICATE_CLIENT_ORDER_ID` / "This order was already submitted." |

### 4.4 Data Retention

| Data | Retention Policy |
|------|-----------------|
| User records | Retained indefinitely (no deletion in MVP; account deactivation is out of scope). |
| Wallet records | Retained indefinitely. |
| WalletTransaction records | Retained indefinitely (audit log; immutable). |
| Order records | Retained indefinitely. |
| Trade records | Retained indefinitely. |
| Candlestick (OHLCV) data | 1m candles: 90 days. 5m/15m/1h: 1 year. 4h/1d: indefinite. Older 1m data downsampled to higher resolutions if needed. |
| Session tokens (Redis) | Access token: 60 min TTL. Refresh token: 7 days TTL. |
| Order book depth snapshot (Redis) | TTL 30 seconds; refreshed on every WebSocket tick. |
| Kafka events | 7 days retention on all topics (MVP default; adequate for replay/debug). |

---

## 5. Interface Requirements

### 5.1 User Interface Requirements

High-level functional expectations per screen. Detailed wireframes/visual design are out of scope for this SRS.

**Login / Register screen:** Email + password inputs. Submit buttons for Login and Register. Error messages displayed inline. On successful login, redirect to Wallet Overview.

**Wallet Overview screen:** Table of all wallets for the user showing assetCode, total, available, frozen, with a USDT-equivalent column (using current price). Buttons for "Deposit" (USDT only, opens modal) and "Withdraw" (opens modal with asset selector).

**Trade screen:** The main trading UI. Layout: top bar (pair selector, current price, 24h change), left panel (TradingView chart), right panel (order book + order entry form), bottom panel (open orders, order history, trade history tabs). Order entry form supports Market and Limit modes with fields adapting to the mode.

**Order History screen:** Paginated table of all orders with filters (status, pair, date range). Each row shows orderId, pair, side, type, price, quantity, filledQuantity, status, createdAt. Row expand shows associated trades.

**Trade History screen:** Paginated table of all trades with the same filters. Each row shows tradeId, pair, side, price, quantity, fee, executedAt.

**Deposit modal:** USDT-only. Amount input with client-side validation (max 100,000). Submit triggers instant confirm and updates Wallet Overview.

**Withdraw modal:** Asset selector (any asset with balance > 0). Amount input with max = available balance. Submit triggers instant confirm.

### 5.2 External Interface Requirements

| Interface | External System | Protocol | Data Format | Direction | Frequency |
|-----------|----------------|----------|-------------|-----------|-----------|
| Binance Klines REST | Binance | HTTPS REST | JSON | Inbound | On-demand + startup backfill |
| Binance ExchangeInfo REST | Binance | HTTPS REST | JSON | Inbound | Once at service startup (+ reload every 6h) |
| Binance Depth REST (snapshot) | Binance | HTTPS REST | JSON | Inbound | On demand (fallback when WS depth is stale) |
| Binance Kline WebSocket | Binance | WSS | JSON | Inbound | Continuous |
| Binance Trade WebSocket | Binance | WSS | JSON | Inbound | Continuous |
| Binance Depth WebSocket (`@depth@100ms`) | Binance | WSS | JSON | Inbound | Continuous (100 ms intervals) |
| Host App SSO (Stage 2) | Education platform | HTTPS REST (OAuth2/OIDC) | JWT | Inbound | Per-user-session |
| Inter-service Kafka events | Internal | Kafka protocol | JSON (schema in Appendix) | Both | Real-time |
| Inter-service HTTP calls | Internal | HTTP/1.1 | JSON | Both | Request-response |

### 5.3 Report Requirements

MVP does not include instructor reports or analytics dashboards (deferred to post-MVP per BR-019). The following user-facing views serve as the MVP's "reports":

| ID | View Name | Description | Audience | Frequency | Format |
|----|-----------|-------------|----------|-----------|--------|
| RPT-001 | Portfolio Summary | Total portfolio value in USDT across all wallets using current prices. Displayed on Wallet Overview. | Learner | On demand (real-time) | Web UI |
| RPT-002 | Order History Export | Downloadable CSV of user's orders. | Learner | On demand | CSV |
| RPT-003 | Trade History Export | Downloadable CSV of user's trades. | Learner | On demand | CSV |

---

## 6. Non-Functional Requirements (Detailed)

### 6.1 Performance

- **SR-NFR-001** (traces NFR-001): Order placement round-trip (client request → service acknowledgment) ≤ 500 ms at 95th percentile, measured under load of 100 concurrent users submitting orders at 1 order/user/second.
- **SR-NFR-002** (traces NFR-002): A price update from Binance WebSocket to the user's browser (visible as chart update) ≤ 2 seconds at 95th percentile.
- **SR-NFR-003**: Order matching simulation tick evaluation (iterate all open orders for a pair, check fill eligibility) ≤ 50 ms at 95th percentile with up to 1,000 open orders per pair.
- **SR-NFR-004**: Wallet read endpoint (`GET /wallets/me`) ≤ 100 ms at 95th percentile for a user with up to 10 wallets.
- **SR-NFR-005**: TradingView UDF history endpoint (`/udf/history`) ≤ 1 second at 95th percentile for a 30-day 1m range.

### 6.2 Security

- **SR-NFR-010** (traces NFR-008): All passwords hashed with Argon2id (preferred) or bcrypt cost factor ≥ 12. Plain text passwords are never logged, never persisted, never stored in Redis.
- **SR-NFR-011**: JWT access tokens signed with RS256; private key stored in secrets vault, never in source control. Access token expiry: 60 min. Refresh token: 7 days, stored server-side (Redis) for revocation.
- **SR-NFR-012**: All API endpoints (except login, register, public market data) require a valid JWT.
- **SR-NFR-013**: All client-server traffic over HTTPS (TLS 1.2+) in any non-local environment.
- **SR-NFR-014**: Inter-service communication inside the Docker network does not require TLS in MVP (single-host deployment). Production hardening is a post-MVP item.
- **SR-NFR-015**: Standard OWASP Top 10 protections applied: input validation on every endpoint, parameterized SQL (JPA), output encoding for UI, CSRF protection for state-changing operations initiated from the browser, HttpOnly + Secure cookies for any browser-held tokens.
- **SR-NFR-016**: Rate limiting at API Gateway: 60 RPS sustained per user, 120 burst; login endpoint: 10 attempts / 10 minutes per email.
- **SR-NFR-017**: A user can access only their own wallets, orders, and trades. Authorization checks on every read/write endpoint using the `userId` from the validated JWT.
- **SR-NFR-018**: Audit log (WalletTransaction) is append-only; no update or delete DML is allowed on this table. Enforced at the ORM/repository layer.

### 6.3 Availability & Reliability

- **SR-NFR-020** (traces NFR-004): Best-effort 99% uptime during POC phase. No formal SLA.
- **SR-NFR-021** (traces NFR-013): If Binance is unreachable for > 30 seconds, the system enters degraded mode for affected pairs: open limit orders remain resting (not cancelled), market orders are rejected with `PRICE_FEED_UNAVAILABLE`, chart UI shows "stale data" banner. Normal operation resumes within 5 seconds of Binance recovery.
- **SR-NFR-022**: PostgreSQL and Kafka are single-node in MVP (docker-compose). RTO and RPO are undefined at MVP scale (acceptable risk per BRD constraint).
- **SR-NFR-023**: Each microservice exposes `/actuator/health` returning liveness and readiness. Readiness includes downstream checks (DB, Kafka, Redis).

### 6.4 Scalability

- **SR-NFR-030** (traces NFR-003): The deployed MVP stack runs on a single developer workstation (docker-compose, 32 GB RAM). It must support 100 concurrent active users without horizontal scaling.
- **SR-NFR-031**: Each service must be stateless (no in-process session state) or have clearly documented state (e.g., Matching Engine holds in-memory open-order cache per pair; that state is rebuildable from DB at startup).
- **SR-NFR-032**: Kafka topics are partitioned by `userId` (for user-scoped events) or `pairSymbol` (for market events) to enable future horizontal scaling.

### 6.5 Usability

- **SR-NFR-040**: Desktop browsers supported: latest two versions of Chrome, Firefox, Edge. Safari best-effort.
- **SR-NFR-041**: Minimum viewport 1024 px wide. Mobile layouts deferred.
- **SR-NFR-042**: UI language: English for MVP. Vietnamese deferred (per BRD Section 6.2).
- **SR-NFR-043**: Accessibility: WCAG 2.1 Level A minimum (basic keyboard navigation, semantic HTML, `alt` text for images). Level AA is aspirational, not blocking.

### 6.6 Maintainability

- **SR-NFR-050** (traces NFR-010): Each microservice has its own Git module, its own PostgreSQL database/schema, and its own Docker image. No service reads or writes another service's database directly.
- **SR-NFR-051**: All inter-service dependencies are declared explicitly — Kafka topic contracts and HTTP endpoint contracts. Breaking changes require a version bump.
- **SR-NFR-052**: Shared code (enums like `OrderSide`, value objects, Kafka event schemas) lives in an `exchange-common` Maven module with no business logic and no database dependencies.
- **SR-NFR-053**: Every service has a README.md covering: purpose, owned entities, exposed APIs, consumed/produced Kafka topics, local dev setup.

### 6.7 Observability

- **SR-NFR-060** (traces NFR-012): Every service produces structured logs (JSON format) including `timestamp`, `level`, `service`, `userId` (if available), `correlationId`, `message`. Log level: INFO in normal operation, DEBUG toggleable per service.
- **SR-NFR-061**: A `correlationId` (UUID) is propagated through all inter-service calls via HTTP header `X-Correlation-Id` and Kafka message header. Generated at API Gateway if not provided.
- **SR-NFR-062**: OpenTelemetry / Jaeger tracing is a post-MVP item; MVP relies on structured logs + correlationId for tracing.

### 6.8 External Dependency Resilience

- **SR-NFR-070** (traces NFR-013): Binance API calls use a circuit breaker (Resilience4j) with failure threshold 50% over 20 calls, open state duration 30 seconds, half-open trial 3 calls.
- **SR-NFR-071**: The feed provider interface (`MarketDataFeedProvider`) is abstracted; switching to an alternate provider (e.g., CoinGecko fallback) requires implementing the interface and updating configuration. No change to business services.

---

## 7. Constraints & Assumptions

### 7.1 Technical Constraints (from BRD §9)

- Backend: Spring Boot 3.x, Java 21, Maven (multi-module).
- Frontend: Next.js (React 18+), must be embeddable into an existing Next.js app.
- Charting: TradingView Lightweight Charts v5.
- Databases: PostgreSQL (transactional), Redis (cache/sessions), TimescaleDB (OHLCV).
- Messaging: Kafka.
- Container: Docker / docker-compose.
- No Kubernetes, no service mesh in MVP.
- No shared database across services.

### 7.2 Assumptions

- Binance public market data APIs remain freely accessible throughout MVP development.
- Binance rate limits (weight-based, publicly documented) are sufficient for 5 pairs with WebSocket streams.
- The host education platform will agree to an OAuth2/OIDC SSO contract compatible with Spring Security defaults for Stage 2.
- Decimal arithmetic (BigDecimal with HALF_UP rounding) is sufficient precision for all monetary calculations; no banker's rounding or special tax rounding rules apply.
- The solo developer has sufficient infrastructure credit / self-hosting capacity for a single-host docker-compose deployment.

---

## 8. Error Handling & Edge Cases (System-Wide)

This section covers cross-cutting error scenarios. Service-specific edge cases live in each service's appendix.

### 8.1 External Feed Unavailable

- **Scenario 1:** Binance WebSocket disconnects mid-session. **Behavior:** Market Data Service attempts reconnection with exponential backoff. During disconnect, cached prices are served with a `stale: true` flag; order book and chart show a "reconnecting" banner. If disconnect > 30s, new market orders are rejected with `PRICE_FEED_UNAVAILABLE`.
- **Scenario 2:** Binance REST endpoints return 429 (rate limit). **Behavior:** Circuit breaker opens; subsequent requests are failed fast with `UPSTREAM_RATE_LIMITED`. Half-open trial after 30 seconds.
- **Scenario 3:** Binance returns malformed JSON or unexpected schema. **Behavior:** Parser logs ERROR with payload sample; message is dropped; no downstream event is published. A circuit breaker on "parse failures" opens if > 10% failure rate over 1 minute.

### 8.2 Concurrent Wallet Mutations

- **Scenario 1:** Two concurrent orders from the same user attempt to freeze the same balance. **Behavior:** Optimistic locking (`@Version`) on Wallet catches the conflict; the losing transaction retries up to 3 times; if all retries fail, the caller receives HTTP 409 `CONCURRENT_MODIFICATION` and retries at application level. For known high-contention paths (e.g., batch order placement), pessimistic lock (`SELECT ... FOR UPDATE`) is used instead.
- **Scenario 2:** A trade execution event arrives while a cancellation is in flight. **Behavior:** Idempotent consumer — Wallet Service uses `tradeId` as idempotency key; double delivery is detected and ignored. Order Service resolves the race by state machine: if cancellation hits `NEW → CANCELLED` before the trade event, the trade is rejected with `ORDER_ALREADY_CANCELLED`; if the trade hits first, cancellation on `FILLED` returns `ORDER_NOT_CANCELLABLE`.

### 8.3 Partial Failures in Multi-Step Flows

- **Scenario:** Order Service accepts an order and publishes `OrderPlaced`; Wallet Service fails to freeze balance. **Behavior:** This cannot happen in MVP because balance freeze is synchronous (HTTP call from Order to Wallet) and completes before the order is marked `NEW` and event is published. The sequence is: (1) validate order, (2) call Wallet to freeze (blocking HTTP), (3) persist Order as `NEW`, (4) publish `OrderPlaced`, (5) return 201 to client. Step (3) and (4) use Transactional Outbox to avoid inconsistency between DB commit and event publish.
- **Scenario:** Matching Engine publishes `TradeExecuted` but Wallet Service crashes before consuming it. **Behavior:** Kafka retains the message; Wallet Service resumes consumption on restart; idempotent processing (by `tradeId`) prevents double-application.

### 8.4 Invalid Input

All endpoints validate input at boundary (controller layer) using Bean Validation (`@Valid`, `@NotNull`, custom validators). Invalid input returns HTTP 400 with error code and a human-readable message. No invalid data reaches the service layer.

### 8.5 Boundary Conditions

| Condition | Limit | Behavior on Breach |
|-----------|-------|-------------------|
| Request body size | 10 KB | HTTP 413 `REQUEST_TOO_LARGE` |
| Max open orders per user | 50 | HTTP 400 `MAX_OPEN_ORDERS_REACHED` on new order placement |
| Max active wallets per user | One per supported asset (bounded by asset catalog ~ 10 in MVP) | N/A (system-controlled, not user-controlled) |
| Max concurrent WebSocket connections per user | 3 | New connection closes the oldest |
| Trading pair count | 5 in MVP, expandable via config | N/A |
| Historical candle query range | Max 1,000 candles per request | HTTP 400 `RANGE_TOO_LARGE`; client paginates |

---

## 9. State Diagrams

### 9.1 Order Lifecycle

```
        ┌─────────────┐
        │  NEW        │◄──── order accepted, funds frozen
        └──┬──────┬───┘
           │      │
  partial  │      │ user cancels
    fill   │      │
           ▼      ▼
   ┌──────────┐  ┌────────────┐
   │PARTIALLY │  │ CANCELLED  │
   │  FILLED  │  └────────────┘
   └────┬──┬──┘
        │  │
fill    │  │ user cancels
complete│  │
        ▼  ▼
   ┌──────────┐  ┌────────────┐
   │  FILLED  │  │ CANCELLED  │
   └──────────┘  └────────────┘

Rejected-at-acceptance path:
   submit → validation fails → REJECTED (no funds frozen)
```

Detailed transitions, trigger events, and invariants are in `SRS_Appendix_OrderService.md` §2.

### 9.2 Deposit Lifecycle (Simulated)

```
   submit → PENDING (microseconds) → CONFIRMED
```

Per bro's decision, deposits are instant-confirmed in MVP. `PENDING` is a transient state within a single database transaction and not externally observable; if a future change reintroduces delays, the `DepositRecord.status` field already supports it.

### 9.3 Withdrawal Lifecycle (Simulated)

```
   submit → PENDING (microseconds) → CONFIRMED
```

Same pattern as deposit — instant-confirmed in MVP.

---

## 10. Traceability Matrix (BRD → SRS)

| BRD ID | SRS Requirements | BRD Priority |
|--------|-----------------|--------------|
| BR-001 | SR-001, SR-002, SR-003, SR-004, SR-005, SR-007 | Must |
| BR-002 | SR-010, SR-011 | Must |
| BR-003 | SR-013, SR-014, SR-015, SR-016, SR-017, SR-018 | Must |
| BR-004 | SR-030, SR-033, SR-036, SR-040, SR-042, SR-050, SR-051, SR-052, SR-065 | Must |
| BR-005 | SR-031, SR-032, SR-033, SR-036, SR-040, SR-042, SR-050, SR-053, SR-054, SR-065 | Must |
| BR-006 | SR-038, SR-039, SR-040 | Must |
| BR-007 | SR-041 | Must |
| BR-008 | SR-055, SR-056 | Must |
| BR-009 | SR-012, SR-021 | Must |
| BR-010 | SR-019, SR-022 | Must |
| BR-011 | SR-060, SR-061, SR-062, SR-063, SR-067, SR-072, SR-074 | Must |
| BR-012 | SR-062, SR-064, SR-069, SR-074 | Must |
| BR-013 | SR-057, SR-058 | Must |
| BR-014 | SR-060, SR-062, SR-069 | Must |
| BR-015 | SR-070, SR-071, SR-073, SR-075 | Must |
| BR-016 | SR-006, SR-075 | Should |
| BR-017 | SR-066 | Should |
| BR-018 | Addressed by SR-071 / architectural choice; no MVP SR | Could |
| BR-019 | No MVP SR (post-MVP) | Could |
| BR-020 | SR-032 notes deferral | Could |

NFR traceability is embedded in Section 6 (each SR-NFR-xxx cites the originating NFR).

---

## 11. Risks (SRS-Level Additions to BRD §10)

The BRD risk register is authoritative at the business level. Implementation revealed one additional risk during SRS elaboration:

| ID | Risk | Likelihood | Impact | Mitigation |
|----|------|-----------|--------|------------|
| R-011 | Matching Engine depth-walk and partial-fill-by-real-volume (SR-052, SR-054) require Binance WebSocket `@depth` and `@trade` streams for 5 pairs. The initial Market Data Service design (per userMemories: REST-based) did not include these streams. Scope of Market Data Service must expand to include WebSocket ingestion and buffering. | High (already materialized) | Medium | Explicit change of scope in Market Data Service appendix. Buffer trade stream 5 seconds in memory (Redis as overflow). Budget 2–3 extra dev days for this expansion. If schedule slips, fall back to simpler matching (fill at best bid/ask, always full fill) — documented as Plan B. |

---

## 12. Appendices

### Appendix A: Glossary

See Section 1.3 for definitions.

### Appendix B: Per-Service Detail Documents

Authoritative per-service specs:

- `SRS_Appendix_WalletService.md` — Wallet entities, balance operations, deposit/withdrawal flows, concurrency strategy, Kafka consumers/producers, REST API.
- `SRS_Appendix_OrderService.md` — Order entity, state machine, validation pipeline, idempotency, REST API, Kafka producers.
- `SRS_Appendix_MatchingEngine.md` — Simulation algorithm (market fill, limit fill, walk-the-book, partial fill), fee computation, Kafka consumers/producers.
- `SRS_Appendix_MarketDataService.md` — Binance REST/WS integration, TimescaleDB schema, TradingView UDF endpoints, Redis cache, exchangeInfo sync.
- `SRS_Appendix_UserAuthService.md` — Registration, login, JWT/refresh flow, rate limiting, Stage 2 SSO integration design.

### Appendix C: Kafka Topic & Event Catalog (Overview)

Full schemas in each service's appendix. Partition keys noted for scalability.

| Topic | Producer | Consumers | Partition Key | Payload (summary) |
|-------|----------|-----------|---------------|-------------------|
| `order.placed` | Order Service | Matching Engine, Wallet Service (for logging) | `userId` | orderId, userId, pair, side, type, price, quantity, createdAt |
| `order.cancelled` | Order Service | Matching Engine, Wallet Service | `userId` | orderId, userId, cancelledAt |
| `order.rejected` | Order Service | (log only) | `userId` | orderId, userId, reason |
| `trade.executed` | Matching Engine | Order Service, Wallet Service | `userId` | tradeId, orderId, userId, pair, side, price, quantity, fee, role, executedAt |
| `wallet.transaction` | Wallet Service | (log only) | `userId` | txnId, walletId, type, amount, referenceId, createdAt |
| `market.kline.{pair}` | Market Data Service | Frontend (via WS Gateway) | `pair` | Binance kline payload |
| `market.trade.{pair}` | Market Data Service | Matching Engine | `pair` | price, qty, time, side |
| `market.depth.{pair}` | Market Data Service | Matching Engine, Frontend (via WS Gateway) | `pair` | bids[], asks[], updateId |

### Appendix D: Open Questions for Stage 2

These are documented for Stage 2 planning and are **not** in MVP scope. Listed here so they are not lost.

1. Host app SSO contract: confirm OAuth2/OIDC is acceptable, or is it a simpler JWT pass-through?
2. Will user accounts be merged between Haizz Exchange and the host education platform, or federated (Haizz holds a shadow user linked to host userId)?
3. Will instructors have read access to learner activity from Stage 2 day 1, or is that a later phase?
4. iframe vs Next.js module import — which embedding model does the host app prefer?
5. CSS isolation strategy — CSS Modules, Tailwind with scoped prefix, or Shadow DOM?

---

**End of SRS.md. See per-service appendices for implementation-level detail.**
