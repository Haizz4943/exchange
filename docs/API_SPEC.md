# API Specification

**Project Name:** Simulated Crypto Trading Platform — *Haizz Exchange*
**Version:** 1.0
**Date:** April 25, 2026
**Author:** Haizz (Product Owner & Developer)
**Status:** Draft — pending review
**Related Documents:** `BRD.md` v1.0, `SRS.md` v1.0, `SystemDesign.md` v1.0, `DEV_GUIDE.md` v1.0

---

## 0. Conventions

**Base URL:** All public endpoints are accessed through the API Gateway at `http://localhost:8080` (dev) or `https://api.haizz.io` (prod).

**Authentication:** Unless marked "None", every endpoint requires `Authorization: Bearer <JWT>` header. The Gateway validates the JWT and injects `X-User-Id` (UUID) and `X-Correlation-Id` (UUID) headers downstream.

**Monetary values:** Always JSON strings (never floating-point numbers) to avoid JS precision loss. Backend uses `BigDecimal`; frontend parses with appropriate precision libraries.

**Timestamps:** ISO-8601 with millisecond precision, UTC. Example: `"2026-04-25T10:30:15.123Z"`.

**IDs:** UUID v4 strings everywhere. No sequential integers.

**Pagination envelope:**
```json
{
  "content": [ ... ],
  "page": 0,
  "size": 50,
  "total_elements": 120,
  "total_pages": 3
}
```

**Error envelope (all services):**
```json
{
  "timestamp": "2026-04-25T10:15:30.123Z",
  "status": 400,
  "error": "Bad Request",
  "code": "INSUFFICIENT_AVAILABLE_BALANCE",
  "message": "Insufficient available balance. Cancel open orders to free frozen balance.",
  "details": {
    "available": "10000.00000000",
    "requested": "20000.00000000",
    "frozen": "60000.00000000"
  },
  "path": "/api/v1/withdrawals",
  "correlationId": "7d3e-...-..."
}
```

- `code`: Machine-readable SCREAMING_SNAKE, stable within major version.
- `message`: Human-readable English; FE may override with localized text based on `code`.
- `details`: Open-shape object, specific to the error. Absent on 5xx.

---

## 1. Auth Service (`/api/v1/auth/*`)

### 1.1 Register

```
POST /api/v1/auth/register
Auth: None
```

**Request:**
```json
{
  "email": "alice@example.com",
  "password": "Secret1234!"
}
```

**Response — 201 Created:**
```json
{
  "user_id": "7d3e4f5a-...",
  "email": "alice@example.com",
  "created_at": "2026-04-25T10:00:00.000Z"
}
```

> Note: Registration does NOT return tokens. User must call `/login` to authenticate.

**Errors:**

| Code | HTTP | Condition |
|------|------|-----------|
| `INVALID_EMAIL` | 400 | Malformed email |
| `PASSWORD_TOO_WEAK` | 400 | Fails complexity rules (min 8 chars, 1 uppercase, 1 digit) |
| `EMAIL_ALREADY_EXISTS` | 409 | Email already registered (case-insensitive) |
| `RATE_LIMIT_EXCEEDED` | 429 | Too many registration attempts |

**Side effects:**
- Publishes `UserRegistered` event to Kafka → Wallet Service provisions 6 wallets (USDT with 10,000 initial balance + BTC, ETH, BNB, SOL, XRP with 0).

---

### 1.2 Login

```
POST /api/v1/auth/login
Auth: None
```

**Request:**
```json
{
  "email": "alice@example.com",
  "password": "Secret1234!"
}
```

**Response — 200 OK:**
```json
{
  "access_token": "eyJhbGciOiJSUzI1NiIs...",
  "refresh_token": "rt_abc123def456...",
  "token_type": "Bearer",
  "expires_in": 3600,
  "user": {
    "user_id": "7d3e4f5a-...",
    "email": "alice@example.com"
  }
}
```

**Errors:**

| Code | HTTP | Condition |
|------|------|-----------|
| `INVALID_CREDENTIALS` | 401 | Wrong email or password (timing-equalized — no user enumeration) |
| `USER_DISABLED` | 403 | Account suspended |
| `RATE_LIMIT_EXCEEDED` | 429 | 10 attempts / 10 minutes per email |

**JWT claims (`access_token` decoded):**
```json
{
  "sub": "7d3e4f5a-...",
  "email": "alice@example.com",
  "roles": ["user"],
  "iss": "haizz-exchange",
  "aud": "haizz",
  "iat": 1745570400,
  "exp": 1745574000
}
```

---

### 1.3 Refresh Token

```
POST /api/v1/auth/refresh
Auth: None (token in body)
```

**Request:**
```json
{
  "refresh_token": "rt_abc123def456..."
}
```

**Response — 200 OK:** Same shape as login response with new token pair.

**Errors:**

| Code | HTTP | Condition |
|------|------|-----------|
| `INVALID_REFRESH_TOKEN` | 401 | Token not found or malformed |
| `REFRESH_TOKEN_EXPIRED` | 401 | Token past expiry (7 days TTL) |
| `REFRESH_TOKEN_REVOKED` | 401 | Token was already revoked |

**Security — reuse detection:** If a revoked refresh token is presented again, ALL active sessions for that user are revoked (compromise indicator). Logged as SECURITY event.

---

### 1.4 Logout

```
POST /api/v1/auth/logout
Auth: Bearer <access_token>
```

**Request:**
```json
{
  "refresh_token": "rt_abc123def456..."
}
```

**Response — 204 No Content**

Revokes the specified session. Access token is stateless JWT — expires naturally (1h TTL).

---

### 1.5 Get Current User

```
GET /api/v1/auth/me
Auth: Bearer <access_token>
```

**Response — 200 OK:**
```json
{
  "user_id": "7d3e4f5a-...",
  "email": "alice@example.com",
  "external_provider": "local",
  "status": "ACTIVE"
}
```

---

### 1.7 Internal Endpoints

| Method | Path | Purpose |
|--------|------|---------|
| `POST` | `/internal/auth/validate-token` | Validate access token (fallback for Gateway) |
| `GET` | `/internal/auth/public-key` | Fetch RS256 public key (JWKS) |

**Token Validation Request:**
```json
{ "token": "eyJhbGci..." }
```

**Token Validation Response:**
```json
{ "valid": true, "user_id": "7d3e...", "expires_at": "2026-04-25T11:00:00Z" }
```
or
```json
{ "valid": false, "reason": "EXPIRED" }
```

---

## 2. Wallet Service (`/api/v1/wallets/*`, `/api/v1/deposits/*`, `/api/v1/withdrawals/*`)

### 2.1 List My Wallets

```
GET /api/v1/wallets/me
Auth: Bearer
```

**Response — 200 OK:**
```json
{
  "wallets": [
    {
      "walletId": "a1b2c3d4-...",
      "assetCode": "USDT",
      "total": "10000.000000",
      "available": "4000.000000",
      "frozen": "6000.000000"
    },
    {
      "walletId": "e5f6g7h8-...",
      "assetCode": "BTC",
      "total": "0.50000000",
      "available": "0.50000000",
      "frozen": "0.00000000"
    }
  ]
}
```

---

### 2.2 List Wallet Transactions

```
GET /api/v1/wallet-transactions?asset=USDT&type=TRADE_DEBIT&page=0&size=50
Auth: Bearer
```

**Query params:**

| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `asset` | String | all | Filter by asset code |
| `type` | String | all | Filter by txn type (comma-separated) |
| `page` | int | 0 | Page number |
| `size` | int | 50 | Items per page (max 100) |

**Response — 200 OK:**
```json
{
  "content": [
    {
      "txnId": "t1-...",
      "walletId": "a1b2-...",
      "assetCode": "USDT",
      "type": "TRADE_DEBIT",
      "amount": "-5500.000000",
      "balanceBefore": "10000.000000",
      "balanceAfter": "4500.000000",
      "referenceId": "trade-uuid-...",
      "referenceType": "TRADE",
      "createdAt": "2026-04-25T10:30:00.000Z"
    }
  ],
  "page": 0,
  "size": 50,
  "total_elements": 23,
  "total_pages": 1
}
```

**Transaction types:** `SIGNUP_GRANT`, `DEPOSIT`, `WITHDRAWAL`, `ORDER_FREEZE`, `ORDER_UNFREEZE`, `TRADE_CREDIT`, `TRADE_DEBIT`, `FEE`

---

### 2.3 Submit Deposit

```
POST /api/v1/deposits
Auth: Bearer
```

**Request:**
```json
{
  "assetCode": "USDT",
  "amount": "5000",
  "clientRequestId": "550e8400-e29b-..."
}
```

**Response — 201 Created:**
```json
{
  "depositId": "d1-...",
  "assetCode": "USDT",
  "amount": "5000.000000",
  "status": "CONFIRMED",
  "confirmedAt": "2026-04-25T10:31:00.000Z"
}
```

> MVP: deposits are instantly confirmed (simulated gateway). `clientRequestId` provides idempotency.

**Errors:**

| Code | HTTP | Condition |
|------|------|-----------|
| `UNSUPPORTED_DEPOSIT_ASSET` | 400 | Only USDT allowed in MVP |
| `INVALID_AMOUNT` | 400 | Amount ≤ 0 or > 100,000 |
| `DUPLICATE_REQUEST` | 409 | Same `clientRequestId` within 60s |

---

### 2.4 List Deposits

```
GET /api/v1/deposits?page=0&size=20
Auth: Bearer
```

**Response — 200 OK:** Paginated list of `DepositRecord` objects.

---

### 2.5 Submit Withdrawal

```
POST /api/v1/withdrawals
Auth: Bearer
```

**Request:**
```json
{
  "assetCode": "BTC",
  "amount": "0.1",
  "clientRequestId": "660e8400-..."
}
```

**Response — 201 Created:**
```json
{
  "withdrawalId": "w1-...",
  "assetCode": "BTC",
  "amount": "0.10000000",
  "status": "CONFIRMED",
  "confirmedAt": "2026-04-25T10:32:00.000Z"
}
```

**Errors:**

| Code | HTTP | Condition |
|------|------|-----------|
| `INSUFFICIENT_AVAILABLE_BALANCE` | 400 | Available balance < requested amount |
| `UNSUPPORTED_ASSET` | 400 | Asset not in catalog |
| `INVALID_AMOUNT` | 400 | Amount ≤ 0 |
| `DUPLICATE_REQUEST` | 409 | Same `clientRequestId` within 60s |

---

### 2.6 List Withdrawals

```
GET /api/v1/withdrawals?page=0&size=20
Auth: Bearer
```

**Response — 200 OK:** Paginated list of `WithdrawalRecord` objects.

---

### 2.7 Internal Endpoints

#### Freeze Balance

```
POST /internal/wallets/freeze
Auth: Network-trust (X-Internal-Signature)
```

**Request:**
```json
{
  "userId": "7d3e...",
  "assetCode": "USDT",
  "amount": "60000",
  "referenceType": "ORDER",
  "referenceId": "order-uuid-..."
}
```

**Response — 200 OK:**
```json
{
  "walletId": "a1b2-...",
  "assetCode": "USDT",
  "totalBalance": "70000.000000",
  "availableBalance": "10000.000000",
  "frozenBalance": "60000.000000"
}
```

**Errors:**

| Code | HTTP | Condition |
|------|------|-----------|
| `INSUFFICIENT_AVAILABLE_BALANCE` | 400 | Available < freeze amount |
| `WALLET_NOT_FOUND` | 404 | No wallet for user+asset |
| `DUPLICATE_FREEZE` | 409 | Same referenceId already frozen (idempotent — returns existing state) |

**Idempotency:** Dedupes by `(referenceType=ORDER, referenceId)`. If already frozen, returns 200 with current wallet state.

---

#### Unfreeze Balance

```
POST /internal/wallets/unfreeze
Auth: Network-trust
```

**Request:**
```json
{
  "userId": "7d3e...",
  "assetCode": "USDT",
  "amount": "60000",
  "referenceType": "ORDER",
  "referenceId": "order-uuid-...",
  "reason": "CANCELLED"
}
```

**Response — 200 OK:** Same shape as freeze response with updated balances.

---

#### Check Balance

```
GET /internal/wallets/balance?userId={uuid}&assetCode=USDT
Auth: Network-trust
```

**Response — 200 OK:**
```json
{
  "walletId": "a1b2-...",
  "assetCode": "USDT",
  "totalBalance": "70000.000000",
  "availableBalance": "10000.000000",
  "frozenBalance": "60000.000000"
}
```

---

## 3. Order Service (`/api/v1/orders/*`)

### 3.1 Place Order

```
POST /api/v1/orders
Auth: Bearer
```

**Request:**
```json
{
  "client_order_id": "550e8400-e29b-41d4-a716-446655440000",
  "pair": "BTCUSDT",
  "side": "BUY",
  "type": "LIMIT",
  "quantity": "0.1",
  "limit_price": "55000.00",
  "time_in_force": "GTC"
}
```

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `client_order_id` | UUID | No | Idempotency key; duplicate within 24h → 409 |
| `pair` | String | Yes | e.g., "BTCUSDT" — must be enabled |
| `side` | Enum | Yes | `BUY` \| `SELL` |
| `type` | Enum | Yes | `MARKET` \| `LIMIT` |
| `quantity` | String (decimal) | Yes | Must be > 0, multiple of `stepSize` |
| `limit_price` | String (decimal) | Limit only | Required for LIMIT, must be null/absent for MARKET. Multiple of `tickSize` |
| `time_in_force` | Enum | No | Default `GTC`. MVP: GTC only |

**Response — 201 Created:**
```json
{
  "order_id": "7d3e4f5a-...",
  "client_order_id": "550e8400-...",
  "pair": "BTCUSDT",
  "side": "BUY",
  "type": "LIMIT",
  "quantity": "0.10000000",
  "limit_price": "55000.00000000",
  "time_in_force": "GTC",
  "state": "NEW",
  "filled_qty": "0.00000000",
  "avg_fill_price": null,
  "freeze_amount": "5505.500000",
  "freeze_asset": "USDT",
  "created_at": "2026-04-25T10:12:34.567Z"
}
```

**Errors:**

| Code | HTTP | Condition |
|------|------|-----------|
| `PAIR_NOT_SUPPORTED` | 400 | Pair not in enabled set |
| `INVALID_QUANTITY` | 400 | Quantity ≤ 0 or not a multiple of `stepSize` |
| `INVALID_PRICE` | 400 | Limit price ≤ 0 or not a multiple of `tickSize` |
| `BELOW_MIN_NOTIONAL` | 400 | `price × quantity < minNotional` (10 USDT) |
| `INVALID_SIDE` | 400 | Side not BUY/SELL |
| `INVALID_ORDER_TYPE` | 400 | Type not MARKET/LIMIT |
| `LIMIT_PRICE_REQUIRED` | 400 | Limit order without `limit_price` |
| `LIMIT_PRICE_NOT_ALLOWED` | 400 | Market order with `limit_price` |
| `INSUFFICIENT_AVAILABLE_BALANCE` | 400 | Wallet freeze failed |
| `MAX_OPEN_ORDERS_EXCEEDED` | 400 | ≥ 100 open orders on this pair |
| `DUPLICATE_CLIENT_ORDER_ID` | 409 | Same `client_order_id` used within 24h |
| `MARKET_DATA_UNAVAILABLE` | 503 | Pair feed degraded > 10s |
| `WALLET_SERVICE_UNAVAILABLE` | 503 | Wallet Service timeout/error |

**Placement sequence:**
1. HTTP validation (bean validation)
2. Business validation (pair, stepSize, tickSize, minNotional, max open orders)
3. Idempotency check (`client_order_id`)
4. For MARKET orders: fetch best bid/ask from Market Data Service
5. Compute freeze amount
6. Sync call: Wallet Service `POST /internal/wallets/freeze`
7. Persist Order (state=NEW) + outbox row in same DB transaction
8. Return 201
9. Outbox relay publishes `OrderPlaced` to Kafka asynchronously

**Freeze amount computation:**

| Scenario | Freeze Asset | Freeze Amount |
|----------|-------------|---------------|
| BUY LIMIT | Quote (USDT) | `quantity × limit_price × (1 + takerFeeRate)` |
| BUY MARKET | Quote (USDT) | `quantity × bestAsk × (1 + takerFeeRate) × 1.005` (0.5% slippage buffer) |
| SELL LIMIT | Base (BTC) | `quantity` |
| SELL MARKET | Base (BTC) | `quantity` |

---

### 3.2 Cancel Order

```
DELETE /api/v1/orders/{orderId}
Auth: Bearer
```

**Response — 200 OK:**
```json
{
  "order_id": "7d3e...",
  "state": "CANCEL_REQUESTED"
}
```

> Note: Returns intermediate state `CANCEL_REQUESTED`. Final `CANCELLED` state arrives via WebSocket when Matching Engine confirms.

**Errors:**

| Code | HTTP | Condition |
|------|------|-----------|
| `ORDER_NOT_FOUND` | 404 | Order doesn't exist |
| `FORBIDDEN` | 403 | Not the owner |
| `ORDER_NOT_CANCELLABLE` | 409 | Already in terminal state (FILLED, CANCELLED, REJECTED) |

---

### 3.3 Get Order

```
GET /api/v1/orders/{orderId}
Auth: Bearer
```

**Response — 200 OK:**
```json
{
  "order_id": "7d3e...",
  "client_order_id": "550e8400-...",
  "pair": "BTCUSDT",
  "side": "BUY",
  "type": "LIMIT",
  "quantity": "0.10000000",
  "limit_price": "55000.00000000",
  "time_in_force": "GTC",
  "state": "PARTIALLY_FILLED",
  "filled_qty": "0.05000000",
  "avg_fill_price": "55010.50000000",
  "freeze_amount": "5505.500000",
  "freeze_asset": "USDT",
  "created_at": "2026-04-25T10:12:34.567Z",
  "updated_at": "2026-04-25T10:15:00.123Z"
}
```

**Errors:** 403 (not owner), 404 (not found)

---

### 3.4 List Orders

```
GET /api/v1/orders?pair=BTCUSDT&state=OPEN,PARTIALLY_FILLED&from=2026-04-01&to=2026-04-25&page=0&size=50&sort=created_at,desc
Auth: Bearer
```

**Query params:**

| Param | Type | Default | Notes |
|-------|------|---------|-------|
| `pair` | String | all | Filter by trading pair |
| `state` | String | all | Comma-separated: `NEW,OPEN,PARTIALLY_FILLED,FILLED,CANCEL_REQUESTED,CANCELLED,REJECTED` |
| `from` | ISO date | — | Start date filter |
| `to` | ISO date | — | End date filter |
| `page` | int | 0 | Page number |
| `size` | int | 50 | Items per page (max 500) |
| `sort` | String | `created_at,desc` | Sort field + direction |

**Response — 200 OK:** Paginated `OrderResponse` objects.

**Order states:**

```
NEW → OPEN → PARTIALLY_FILLED → FILLED
                              → CANCEL_REQUESTED → CANCELLED
                              → REJECTED
```

| State | Meaning |
|-------|---------|
| `NEW` | Just placed, not yet processed by Matching Engine |
| `OPEN` | Resting in the order book (limit orders) |
| `PARTIALLY_FILLED` | Some quantity filled, remainder still open |
| `FILLED` | Fully filled (terminal) |
| `CANCEL_REQUESTED` | User requested cancel; awaiting Matching Engine confirmation |
| `CANCELLED` | Confirmed cancelled (terminal) |
| `REJECTED` | Rejected by Matching Engine — e.g., feed degraded for market order (terminal) |

---

### 3.5 List Trading Pairs

```
GET /api/v1/trading-pairs
Auth: None
```

**Response — 200 OK:**
```json
[
  {
    "symbol": "BTCUSDT",
    "baseAsset": "BTC",
    "quoteAsset": "USDT",
    "tickSize": "0.01",
    "stepSize": "0.00001",
    "minNotional": "10",
    "enabled": true
  },
  {
    "symbol": "ETHUSDT",
    "baseAsset": "ETH",
    "quoteAsset": "USDT",
    "tickSize": "0.01",
    "stepSize": "0.0001",
    "minNotional": "10",
    "enabled": true
  }
]
```

**MVP pairs:** BTCUSDT, ETHUSDT, BNBUSDT, SOLUSDT, XRPUSDT

---

### 3.6 List Assets

```
GET /api/v1/assets
Auth: None
```

**Response — 200 OK:**
```json
[
  { "symbol": "USDT", "name": "Tether", "decimals": 6, "enabled": true },
  { "symbol": "BTC", "name": "Bitcoin", "decimals": 8, "enabled": true },
  { "symbol": "ETH", "name": "Ethereum", "decimals": 8, "enabled": true },
  { "symbol": "BNB", "name": "BNB", "decimals": 8, "enabled": true },
  { "symbol": "SOL", "name": "Solana", "decimals": 8, "enabled": true },
  { "symbol": "XRP", "name": "XRP", "decimals": 8, "enabled": true }
]
```

---

### 3.7 Internal Endpoints

#### List Open Orders (Matching Engine startup)

```
GET /internal/orders?state=OPEN,PARTIALLY_FILLED&page=0&size=1000
Auth: Network-trust
```

Returns compact `InternalOrderProjection[]` — lighter than public `OrderResponse`:

```json
{
  "content": [
    {
      "id": "7d3e...",
      "userId": "a1b2...",
      "pair": "BTCUSDT",
      "side": "BUY",
      "type": "LIMIT",
      "quantity": "0.10000000",
      "limitPrice": "55000.00000000",
      "filledQuantity": "0.05000000",
      "createdAt": "2026-04-25T10:12:34.567Z"
    }
  ],
  "page": 0,
  "size": 1000,
  "total_elements": 450,
  "total_pages": 1
}
```

Matching Engine iterates pages until empty.

---

## 4. Trade Service (Matching Engine) (`/api/v1/trades/*`)

### 4.1 List My Trades

```
GET /api/v1/trades?pair=BTCUSDT&from=2026-04-01&to=2026-04-25&page=0&size=50
Auth: Bearer
```

**Query params:**

| Param | Type | Default | Notes |
|-------|------|---------|-------|
| `pair` | String | all | Filter by pair |
| `from` | ISO date | — | Start date |
| `to` | ISO date | — | End date |
| `page` | int | 0 | — |
| `size` | int | 50 | Max 500 |

**Response — 200 OK:**
```json
{
  "content": [
    {
      "tradeId": "t1-...",
      "orderId": "7d3e-...",
      "pair": "BTCUSDT",
      "side": "BUY",
      "price": "55010.50000000",
      "quantity": "0.05000000",
      "quoteQuantity": "2750.52500000",
      "fee": "2.75052500",
      "feeAsset": "USDT",
      "role": "TAKER",
      "executedAt": "2026-04-25T10:15:00.123Z"
    }
  ],
  "page": 0,
  "size": 50,
  "total_elements": 12,
  "total_pages": 1
}
```

**Trade roles:**
- `TAKER`: Market order, or limit order that crossed the spread immediately
- `MAKER`: Limit order that rested and was filled later

---

## 5. Market Data Service (`/api/v1/marketdata/*`, `/udf/*`)

### 5.1 TradingView UDF Endpoints

#### Config

```
GET /udf/config
Auth: None
```

**Response — 200 OK:**
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

**Resolution mapping** (TradingView resolution → internal interval): `"1"`→`1m`, `"5"`→`5m`, `"15"`→`15m`, `"60"`→`1h`, `"240"`→`4h`, `"1D"`→`1d`.

---

#### Symbol Info

```
GET /udf/symbols?symbol=BTCUSDT
Auth: None
```

**Response — 200 OK:**
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

> `pricescale` is derived from the pair's `tick_size`: `pricescale = round(1 / tick_size)` (e.g. BTCUSDT tick_size=0.01 → 100).

---

#### History (OHLCV Bars)

```
GET /udf/history?symbol=BTCUSDT&resolution=1&from=1713600000&to=1713700000&countback=300
Auth: None
```

| Param | Type | Notes |
|-------|------|-------|
| `symbol` | String | e.g., "BTCUSDT" |
| `resolution` | String | "1", "5", "15", "60", "240", "1D" |
| `from` | long | Unix timestamp (seconds) |
| `to` | long | Unix timestamp (seconds) |
| `countback` | int | Optional. Number of bars before `to` to return. When present, `from` is treated as a hint and the server returns up to `countback` latest bars: `from_effective = max(from, to - countback * intervalSeconds)`. |

**Response — 200 OK (data exists):**
```json
{
  "s": "ok",
  "t": [1713600000, 1713600060, 1713600120],
  "o": ["60000.00", "60010.50", "60005.00"],
  "h": ["60050.00", "60025.00", "60020.00"],
  "l": ["59980.00", "60005.00", "59995.00"],
  "c": ["60010.50", "60020.00", "60015.00"],
  "v": ["12.5", "8.3", "15.2"]
}
```

> `o/h/l/c/v` are returned as **strings** (not numbers) to preserve decimal precision. TradingView accepts this in the compact format. `t` remains numeric (epoch seconds).

**Response — 200 OK (no data):**
```json
{
  "s": "no_data",
  "nextTime": 1713599000
}
```

**Response — 200 OK (error — TradingView convention):**
```json
{
  "s": "error",
  "errmsg": "Failed to load history"
}
```

**Errors (via standard error envelope):**

| Code | HTTP | Condition |
|------|------|-----------|
| `PAIR_NOT_SUPPORTED` | 404 | Unknown pair |
| `INVALID_RESOLUTION` | 400 | Resolution not in supported set |
| `RANGE_TOO_LARGE` | 400 | More than 1000 bars requested |

---

### 5.2 Order Book (Depth)

```
GET /api/v1/marketdata/orderbook/{pair}?depth=20
Auth: Bearer
```

**Response — 200 OK:**
```json
{
  "pair": "BTCUSDT",
  "bids": [
    ["60000.00", "0.50000000"],
    ["59999.50", "1.20000000"],
    ["59999.00", "0.80000000"]
  ],
  "asks": [
    ["60000.50", "0.30000000"],
    ["60001.00", "0.80000000"],
    ["60001.50", "2.50000000"]
  ],
  "updated_at": "2026-04-25T10:30:15.123Z",
  "stale": false
}
```

- `bids`/`asks`: Array of `[price, quantity]` pairs. Bids sorted descending, asks ascending.
- `depth`: Max 20 levels per side.
- `stale`: `true` when Binance depth stream is disconnected; shows last known snapshot.

**Errors:** `DEPTH_UNAVAILABLE` (503) — cache miss + REST fallback failed.

---

### 5.3 Ticker

```
GET /api/v1/marketdata/ticker/{pair}
Auth: Bearer
```

**Response — 200 OK:**
```json
{
  "pair": "BTCUSDT",
  "best_bid": "60000.00",
  "best_ask": "60000.50",
  "last_price": "60000.25",
  "updated_at": "2026-04-25T10:30:15.123Z"
}
```

---

### 5.4 Exchange Info (All Pairs)

```
GET /api/v1/marketdata/exchangeInfo
Auth: None
```

**Response — 200 OK:**
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
    }
  ],
  "updated_at": "2026-04-25T00:00:00.000Z"
}
```

---

### 5.5 Internal Endpoints

| Method | Path | Consumer | Notes |
|--------|------|----------|-------|
| `GET` | `/internal/ticker/{pair}` | Order Service | Same shape as public ticker |
| `GET` | `/internal/depth/{pair}?depth=20` | Matching Engine | Same shape as public depth; must serve from Redis < 5ms p99 |
| `GET` | `/internal/pairs/{pair}/metadata` | Order Service | tickSize, stepSize, minNotional, status |
| `GET` | `/internal/market-data/health` | Matching Engine, Order Service | Per-pair feed health |

**Pair Metadata Response:**
```json
{
  "symbol": "BTCUSDT",
  "base_asset": "BTC",
  "quote_asset": "USDT",
  "tick_size": "0.01",
  "step_size": "0.00001",
  "min_notional": "10",
  "status": "TRADING",
  "updated_at": "2026-04-25T00:00:00.000Z"
}
```

**Health Response:**
```json
{
  "pairs": {
    "BTCUSDT": {
      "trade_last_update": "2026-04-25T10:45:12.000Z",
      "depth_last_update": "2026-04-25T10:45:11.850Z",
      "trade_rate_per_sec": 3.2,
      "status": "HEALTHY"
    },
    "ETHUSDT": {
      "trade_last_update": "2026-04-25T10:45:10.000Z",
      "depth_last_update": "2026-04-25T10:45:09.900Z",
      "trade_rate_per_sec": 2.1,
      "status": "HEALTHY"
    }
  },
  "binance_ws_connected": true,
  "overall_status": "HEALTHY"
}
```

**Feed status semantics:**
- `HEALTHY`: last update < 2s ago
- `STALE`: 2–10s since last update
- `DEGRADED`: > 10s since last update
- `DISCONNECTED`: WS connection is down

---

## 6. Gateway Routing Table

| Path Pattern | Target Service | Auth Required |
|-------------|---------------|---------------|
| `/api/v1/auth/**` | Auth Service (:8081) | No (auth endpoints handle their own) |
| `/api/v1/wallets/**` | Wallet Service (:8082) | Yes |
| `/api/v1/deposits/**` | Wallet Service (:8082) | Yes |
| `/api/v1/withdrawals/**` | Wallet Service (:8082) | Yes |
| `/api/v1/wallet-transactions/**` | Wallet Service (:8082) | Yes |
| `/api/v1/orders/**` | Order Service (:8083) | Yes |
| `/api/v1/trading-pairs/**` | Order Service (:8083) | No |
| `/api/v1/assets/**` | Order Service (:8083) | No |
| `/api/v1/trades/**` | Matching Engine (:8084) | Yes |
| `/udf/**` | Market Data Service (:8085) | No (rate-limited) |
| `/api/v1/marketdata/**` | Market Data Service (:8085) | Yes |
| `/ws` | Gateway WS Handler | Yes (JWT in handshake) |
| `/internal/**` | NOT routed | — (direct service-to-service only) |

**Rate limiting:** 60 RPS sustained per user, 120 burst. Login endpoint: 10 attempts / 10 min per email.

**Headers injected by Gateway on authenticated requests:**
- `X-User-Id: <uuid>` — extracted from JWT `sub` claim
- `X-User-Roles: user` — from JWT `scope` claim (Auth emits `scope`, not `roles`; back-ported 2026-06). `X-User-Email` and `X-User-Scope` are also injected.
- `X-Correlation-Id: <uuid>` — generated if absent

> **⚠️ NOTE (back-ported, 2026-06):** The Gateway validates JWTs with **HS256 + shared secret**
> in dev (Auth's RS256 mode uses an ephemeral, unpublished key). See
> `SystemDesign_Appendix_APIGateway.md` §4.

---

## 7. WebSocket Protocol

### 7.1 Connection

```
WSS /ws?token=<JWT_ACCESS_TOKEN>
```

or

```
WSS /ws
Headers: Authorization: Bearer <JWT_ACCESS_TOKEN>
```

- One connection per authenticated user.
- Connection rejected with `4401 Unauthorized` if token is invalid/expired.
- Connection auto-terminated when token expires; client reconnects with refreshed token.

---

### 7.2 Client → Server Messages

> **⚠️ NOTE (back-ported from gateway/FE build, 2026-06):** The channel taxonomy below was
> updated to match the **implemented** frontend (`WsClient.ts`, `OrderBook.tsx`,
> `TradesTape.tsx`, `CandlestickChart.tsx`). Channels are colon-delimited
> `market:<pair>:<stream>`, plus bare `wallet` and `orders`. (The earlier
> `depth.BTCUSDT` / `order.updates` dot-form was never implemented.)

#### Subscribe

```json
{
  "op": "subscribe",
  "channels": ["market:BTCUSDT:kline:1m", "market:BTCUSDT:depth", "market:BTCUSDT:trades", "wallet", "orders"]
}
```

#### Unsubscribe

```json
{
  "op": "unsubscribe",
  "channels": ["market:BTCUSDT:depth"]
}
```

---

### 7.3 Server → Client Messages

> **⚠️ NOTE (back-ported, 2026-06):** Every server→client message is wrapped in a uniform
> envelope and the FE dispatches **by the `schema` field** (not a `type` field):
>
> ```json
> { "channel": "market:BTCUSDT:depth", "schema": "market-data.depth.v1", "payload": { ... }, "timestamp": "..." }
> ```
>
> The `payload` shapes below describe the **contents of `payload`**. The exact `schema`
> values are listed in §7.4. (The flat `{"type": "..."}` form shown historically is not
> what the FE consumes.)

#### Kline Update

```json
{
  "type": "kline",
  "pair": "BTCUSDT",
  "resolution": "1m",
  "openTime": 1745571600,
  "closeTime": 1745571659,
  "open": "60000.00",
  "high": "60050.00",
  "low": "59980.00",
  "close": "60010.50",
  "volume": "12.50000000",
  "closed": false
}
```

- `closed: true` when the candle is finalized.

---

#### Depth Update

```json
{
  "type": "depth",
  "pair": "BTCUSDT",
  "bids": [["60000.00", "0.50"], ["59999.50", "1.20"]],
  "asks": [["60000.50", "0.30"], ["60001.00", "0.80"]],
  "lastUpdateId": 123456789
}
```

Pushed at ≥ 2 Hz.

---

#### Trade Ticker

```json
{
  "type": "trade",
  "pair": "BTCUSDT",
  "price": "60010.50",
  "quantity": "0.05",
  "side": "BUY",
  "timestamp": "2026-04-25T10:30:15.123Z"
}
```

---

#### Order Update (user-scoped)

```json
{
  "type": "orderUpdate",
  "orderId": "7d3e...",
  "state": "PARTIALLY_FILLED",
  "filledQty": "0.05000000",
  "avgFillPrice": "55010.50000000",
  "updatedAt": "2026-04-25T10:15:00.123Z"
}
```

Implicitly scoped to the authenticated user. No userId in payload.

---

#### Wallet Update (user-scoped)

```json
{
  "type": "walletUpdate",
  "asset": "USDT",
  "total": "4500.000000",
  "available": "4500.000000",
  "frozen": "0.000000"
}
```

---

#### Feed Status

```json
{
  "type": "feedStatus",
  "status": "degraded",
  "pairs": ["BTCUSDT"],
  "message": "Binance feed stale for BTCUSDT"
}
```

---

#### Error

```json
{
  "type": "error",
  "code": "UNAUTHORIZED_CHANNEL",
  "channel": "order.updates.other-user-id",
  "message": "You do not have permission to subscribe to this channel."
}
```

---

### 7.4 Channel Reference

| Channel (subscribe) | `schema` (dispatch key) | Auth Scope | Data Source (Kafka topic) |
|---------|-----------|------------|------------|
| `market:<pair>:kline:<resolution>` | `market-data.kline.v1` | Public | `market-data.kline.v1` (raw event) |
| `market:<pair>:depth` | `market-data.depth.v1` | Public | `market-data.depth.v1` (raw event) |
| `market:<pair>:trades` | `market-data.events.v1.ExternalTradeObserved` | Public | `market-data.events.v1` (EventEnvelope) |
| `market:<pair>:ticker` | *(reserved — FE subscribes but has no handler yet)* | Public | — |
| `orders` | `matching.events.v1.OrderPartiallyFilled` / `.OrderFilled` / `.OrderCancelled` | User-scoped | `matching.events.v1` *(deferred)* |
| `wallet` | `wallet.events.v1.WalletTransactionRecorded` | User-scoped | **`wallet.transactions.v1`** (filtered by userId) |

---

### 7.5 Reconnection Protocol

**Client-side:**
1. On `close` with code ≠ 4401: exponential backoff reconnect (1s, 2s, 4s, 8s, max 30s).
2. On `open`: re-subscribe to all previously active channels.
3. On `close` with code 4401 (token expired): trigger token refresh → reconnect with new token.

**Server-side:**
1. Ping every 30s. If no pong within 10s → close connection.
2. Token expiry check on each outbound message (lazy) or via periodic scan.

---

## 8. Kafka Event Catalog

### 8.1 Event Envelope (all events)

```json
{
  "eventId": "uuid-...",
  "eventType": "OrderPlacedEvent",
  "version": 1,
  "timestamp": "2026-04-25T10:12:34.567Z",
  "source": "order-service",
  "correlationId": "corr-uuid-...",
  "payload": { ... }
}
```

### 8.2 Events by Topic

#### `user.events.v1` (partition key: `userId`)

**UserRegistered:**
```json
{
  "userId": "7d3e...",
  "email": "alice@example.com",
  "externalProvider": "local",
  "createdAt": "2026-04-25T10:00:00.000Z"
}
```
Producer: Auth Service → Consumer: Wallet Service (provision wallets)

---

#### `orders.events.v1` (partition key: `orderId`)

**OrderPlaced:**
```json
{
  "orderId": "7d3e...",
  "userId": "a1b2...",
  "pair": "BTCUSDT",
  "side": "BUY",
  "type": "LIMIT",
  "quantity": "0.10000000",
  "limitPrice": "55000.00000000",
  "timeInForce": "GTC",
  "createdAt": "2026-04-25T10:12:34.567Z"
}
```
Producer: Order Service → Consumer: Matching Engine

**OrderCancelRequested:**
```json
{
  "orderId": "7d3e...",
  "userId": "a1b2...",
  "requestedAt": "2026-04-25T10:20:00.000Z"
}
```
Producer: Order Service → Consumer: Matching Engine

---

#### `matching.events.v1` (partition key: `orderId`)

**TradeExecuted:**
```json
{
  "tradeId": "t1-...",
  "orderId": "7d3e...",
  "userId": "a1b2...",
  "pair": "BTCUSDT",
  "side": "BUY",
  "price": "55010.50000000",
  "quantity": "0.05000000",
  "quoteQuantity": "2750.52500000",
  "fee": "2.75052500",
  "feeAsset": "USDT",
  "role": "TAKER",
  "executedAt": "2026-04-25T10:15:00.123Z"
}
```
Producer: Matching Engine → Consumers: Order Service, Wallet Service

**OrderPartiallyFilled:**
```json
{
  "orderId": "7d3e...",
  "filledQty": "0.05000000",
  "remainingQty": "0.05000000",
  "avgFillPrice": "55010.50000000"
}
```

**OrderFilled:**
```json
{
  "orderId": "7d3e...",
  "totalFilledQty": "0.10000000",
  "avgFillPrice": "55010.50000000"
}
```

**OrderCancelled:**
```json
{
  "orderId": "7d3e...",
  "filledQty": "0.05000000",
  "remainingQty": "0.05000000",
  "cancelledAt": "2026-04-25T10:20:05.000Z"
}
```

**OrderRejected:**
```json
{
  "orderId": "7d3e...",
  "reason": "PRICE_FEED_UNAVAILABLE",
  "rejectedAt": "2026-04-25T10:12:35.000Z"
}
```

---

#### `market-data.events.v1` (partition key: `pair`)

**ExternalTradeObserved:**
```json
{
  "pair": "BTCUSDT",
  "binanceTradeId": 123456789,
  "price": "60010.50",
  "quantity": "0.025",
  "buyerIsMaker": true,
  "tradeTime": "2026-04-25T10:30:15.100Z"
}
```
Producer: Market Data Service → Consumer: Matching Engine

**MarketDataFeedDegraded:**
```json
{
  "pair": "BTCUSDT",
  "status": "DEGRADED",
  "lastUpdate": "2026-04-25T10:29:55.000Z",
  "reason": "No trade events for 15s"
}
```

**MarketDataFeedRecovered:**
```json
{
  "pair": "BTCUSDT",
  "status": "HEALTHY",
  "recoveredAt": "2026-04-25T10:30:20.000Z"
}
```

**PairMetadataUpdated:**
```json
{
  "pair": "BTCUSDT",
  "field": "tick_size",
  "oldValue": "0.01",
  "newValue": "0.10",
  "updatedAt": "2026-04-25T00:00:30.000Z"
}
```

---

#### `market-data.depth.v1` (partition key: `pair`)

Ephemeral — consumed by Gateway for WS fan-out. Same shape as WS depth message.

#### `market-data.kline.v1` (partition key: `pair`)

Ephemeral — consumed by Gateway for WS fan-out. Same shape as WS kline message.

---

#### `wallet.transactions.v1` (partition key: `walletId`)

> **⚠️ NOTE (back-ported, 2026-06):** The implemented Wallet Service publishes to topic
> **`wallet.transactions.v1`** (not `wallet.events.v1`), keyed by **`walletId`**, with a
> **raw payload (no EventEnvelope)** that carries **deltas** rather than absolute balances.
> The Gateway fan-out consumes this topic and re-labels it to the FE schema
> `wallet.events.v1.WalletTransactionRecorded`. See the gateway live-balance limitation in
> `SystemDesign_Appendix_APIGateway.md` §7.2.

**WalletTransaction (actual on-wire payload):**
```json
{
  "txnId": "t1-...",
  "walletId": "a1b2-...",
  "userId": "7d3e...",
  "assetCode": "USDT",
  "type": "TRADE_DEBIT",
  "deltaAvailable": "-5500.000000",
  "deltaFrozen": "0.000000",
  "deltaTotal": "-5500.000000",
  "referenceType": "TRADE",
  "referenceId": "trade-uuid-...",
  "createdAt": "2026-04-25T10:15:00.200Z"
}
```
Producer: Wallet Service → Consumer: Gateway (WS fan-out to user).
**Gap:** the FE wallet store expects absolute `available`/`frozen`/`balanceAfter`; this
delta-only payload cannot drive a full balance update from the stream alone (back-port options
above).

---

## 9. Health & Actuator Endpoints

Every backend service exposes:

| Endpoint | Purpose | Auth |
|----------|---------|------|
| `/actuator/health/liveness` | "Am I running?" — returns 200 | None |
| `/actuator/health/readiness` | "Can I serve traffic?" — checks DB, Kafka, Redis | None |
| `/actuator/info` | Build info (git commit, version) | None |
| `/actuator/metrics` | Micrometer metrics (JVM, HTTP, Kafka, custom) | None |

**Not exposed through Gateway** — accessible only within Docker network for monitoring.

---

*End of `API_SPEC.md`.*
