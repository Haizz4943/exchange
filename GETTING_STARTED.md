# Getting Started — Haizz Exchange

## Yêu cầu

| Tool           | Phiên bản |
| -------------- | --------- |
| JDK            | 21        |
| Maven          | 3.9.15    |
| Docker Desktop | 29.2.1    |
| IDE            | VS Code   |
| DB Client      | DBeaver   |
| API Client     | Postman   |

> Maven không cần cài riêng — dùng `mvnw` wrapper có sẵn trong từng service.

---

## Bước 1 — Tạo file `.env`

```bash
cd D:\Project\exchange
copy .env.example .env
```

File `.env` chứa secrets, **không commit** lên git. Mở và kiểm tra nội dung (có thể giữ nguyên giá trị mặc định cho dev).

---

## Bước 2 — Start infrastructure (Docker)

```bash
cd D:\Project\exchange
docker compose up -d
```

Lệnh này sẽ tải image và khởi động 4 container:

| Container            | Image                        | Port | Dùng cho                          |
| -------------------- | ---------------------------- | ---- | --------------------------------- |
| `exchange-postgres`  | postgres:15-alpine           | 5432 | Auth, Wallet, Order, Match DB     |
| `exchange-timescale` | timescale/timescaledb:2.14-pg15 | 5433 | Market Data DB (OHLCV time-series) |
| `exchange-redis`     | redis:7-alpine               | 6379 | Cache (depth, ticker, exchange info) |
| `exchange-kafka`     | apache/kafka:latest          | 9092 | Event bus                         |

### Kiểm tra containers đã healthy chưa

```bash
docker compose ps
```

Chờ đến khi cột `STATUS` hiện `healthy` cho cả 4 container (khoảng 30–60 giây lần đầu do phải pull image).

```
NAME                  STATUS
exchange-postgres     Up X seconds (healthy)
exchange-timescale    Up X seconds (healthy)
exchange-redis        Up X seconds (healthy)
exchange-kafka        Up X seconds (healthy)
```

### Kiểm tra database đã được tạo chưa

```bash
docker exec exchange-postgres psql -U postgres -l
```

Phải thấy: `auth_db`, `wallet_db`, `order_db`, `match_db` trong danh sách.

```bash
docker exec exchange-timescale psql -U postgres -d marketdata_db -c "\dt"
```

Sau khi Market Data Service khởi động lần đầu, Flyway sẽ tự tạo bảng `candlesticks` (hypertable TimescaleDB).

### Kiểm tra Redis

```bash
docker exec exchange-redis redis-cli ping
# Kết quả: PONG
```

### Kiểm tra Kafka

```bash
docker exec exchange-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
```

---

## Bước 3 — Chạy Auth Service (port 8081)

### Cách 1: Dùng Maven Wrapper (terminal)

```ps
cd D:\Project\exchange\services\auth
$env:SPRING_PROFILES_ACTIVE="dev"
.\mvnw spring-boot:run
```

### Cách 2: Dùng VS Code

Xem cấu hình launch.json ở Bước 6.

### Service đã start thành công khi thấy log:

```
Started AuthApplication in X.XXX seconds
Tomcat started on port 8081
```

---

## Bước 4 — Chạy Wallet Service (port 8082)

Wallet Service cần Auth Service đang chạy để nhận event `UserRegistered` qua Kafka và tự động tạo ví cho user mới.

### Cách 1: Dùng Maven Wrapper (terminal)

Mở **terminal mới** (giữ Auth Service đang chạy):

```ps
cd D:\Project\exchange\services\wallet
$env:SPRING_PROFILES_ACTIVE="dev"
.\mvnw spring-boot:run
```

### Cách 2: Dùng VS Code

Xem cấu hình launch.json ở Bước 6.

### Service đã start thành công khi thấy log:

```
Started WalletApplication in X.XXX seconds
Tomcat started on port 8082
```

---

## Bước 5 — Chạy Market Data Service (port 8085)

Market Data Service cần infrastructure đầy đủ (PostgreSQL TimescaleDB + Redis + Kafka) và **kết nối internet** để kéo dữ liệu từ Binance.

> **Lưu ý startup:** Service sẽ chạy qua 3 bước tự động khi khởi động:
> 1. Đồng bộ exchange info từ Binance REST API (~2–5 giây)
> 2. Backfill candlestick lịch sử từ Binance (~1–5 phút tùy số interval và window)
> 3. Kết nối Binance WebSocket stream (5 pairs × trade + depth)
>
> Service chỉ **READY** sau khi cả 3 bước xong — kiểm tra tại `/actuator/health/readiness`.

### Cách 1: Dùng Maven (terminal)

Mở **terminal mới** (giữ Auth + Wallet Service đang chạy):

```ps
cd D:\Project\exchange\services\marketdata
$env:SPRING_PROFILES_ACTIVE="dev"
mvn spring-boot:run
```

### Cách 2: Dùng VS Code

Xem cấu hình launch.json ở Bước 6.

### Service đã start thành công khi thấy log:

```
Started MarketDataServiceApplication in X.XXX seconds
Step 1/3: Syncing exchange info from provider...
ExchangeInfo loaded successfully
Step 2/3: Running candlestick backfill...
Backfill complete
Step 3/3: Connecting WebSocket feed...
Subscribing to 10 Binance streams
Binance WS connected
=== Market Data Service startup sequence complete ===
```

### Kiểm tra readiness

```bash
curl http://localhost:8085/actuator/health/readiness
```

**Response khi sẵn sàng:**
```json
{
  "status": "UP",
  "components": {
    "marketDataReadiness": {
      "status": "UP",
      "details": {
        "exchangeInfoLoaded": true,
        "backfillComplete": true,
        "wsConnected": true
      }
    }
  }
}
```

---

## Bước 6 — Cấu hình VS Code (chạy cả hai service)

Tạo hoặc cập nhật file `.vscode/launch.json` tại root project:

```json
{
  "version": "0.2.0",
  "configurations": [
    {
      "type": "java",
      "name": "Auth Service",
      "request": "launch",
      "mainClass": "com.haizz.exchange.auth.AuthApplication",
      "projectName": "auth",
      "env": {
        "SPRING_PROFILES_ACTIVE": "dev"
      }
    },
    {
      "type": "java",
      "name": "Wallet Service",
      "request": "launch",
      "mainClass": "com.haizz.exchange.wallet.WalletApplication",
      "projectName": "wallet",
      "env": {
        "SPRING_PROFILES_ACTIVE": "dev"
      }
    },
    {
      "type": "java",
      "name": "Market Data Service",
      "request": "launch",
      "mainClass": "com.haizz.exchange.marketdata.MarketDataServiceApplication",
      "projectName": "market-data-service",
      "env": {
        "SPRING_PROFILES_ACTIVE": "dev"
      }
    }
  ],
  "compounds": [
    {
      "name": "All Services",
      "configurations": ["Auth Service", "Wallet Service", "Market Data Service"]
    }
  ]
}
```

Nhấn **F5** hoặc vào tab **Run and Debug** (Ctrl+Shift+D) → chọn từng service riêng hoặc **All Services**.

---

## Bước 7 — Test API

### Auth: Đăng ký tài khoản

```bash
curl -X POST http://localhost:8081/auth/register \
  -H "Content-Type: application/json" \
  -d "{\"email\": \"alice@example.com\", \"password\": \"Secret1234\"}"
```

**Response 201:**
```json
{
  "user_id": "...",
  "email": "alice@example.com",
  "created_at": "..."
}
```

> Wallet Service sẽ tự động nhận event từ Kafka và tạo ví cho user này.

### Auth: Đăng nhập — lấy token

```bash
curl -X POST http://localhost:8081/auth/login \
  -H "Content-Type: application/json" \
  -d "{\"email\": \"alice@example.com\", \"password\": \"Secret1234\"}"
```

**Response 200:**
```json
{
  "access_token": "eyJhbGci...",
  "refresh_token": "abc123...",
  "token_type": "Bearer",
  "expires_in": 3600
}
```

Lưu `access_token` để dùng cho các request sau (thay `<TOKEN>` bên dưới).

### Auth: Lấy thông tin user

```bash
curl http://localhost:8081/auth/me \
  -H "Authorization: Bearer <TOKEN>"
```

### Auth: Refresh token

```bash
curl -X POST http://localhost:8081/auth/refresh \
  -H "Content-Type: application/json" \
  -d "{\"refresh_token\": \"<refresh_token>\"}"
```

### Auth: Logout

```bash
curl -X POST http://localhost:8081/auth/logout \
  -H "Authorization: Bearer <TOKEN>" \
  -H "Content-Type: application/json" \
  -d "{\"refresh_token\": \"<refresh_token>\"}"
```

### Auth: Validate token (internal — dành cho Gateway)

```bash
curl -X POST http://localhost:8081/internal/auth/validate-token \
  -H "Content-Type: application/json" \
  -d "{\"token\": \"<TOKEN>\"}"
```

---

### Wallet: Xem ví của tôi

```bash
curl http://localhost:8082/api/v1/wallets/me \
  -H "Authorization: Bearer <TOKEN>"
```

**Response 200:**
```json
{
  "wallets": [
    { "assetCode": "USDT", "totalBalance": "0", "availableBalance": "0", "frozenBalance": "0" },
    { "assetCode": "BTC",  "totalBalance": "0", "availableBalance": "0", "frozenBalance": "0" }
  ]
}
```

### Wallet: Nạp tiền (deposit)

```bash
curl -X POST http://localhost:8082/api/v1/deposits \
  -H "Authorization: Bearer <TOKEN>" \
  -H "Content-Type: application/json" \
  -d "{\"assetCode\": \"USDT\", \"amount\": \"100.00\", \"clientRequestId\": \"dep-001\"}"
```

### Wallet: Xem lịch sử nạp tiền

```bash
curl "http://localhost:8082/api/v1/deposits?page=0&size=20" \
  -H "Authorization: Bearer <TOKEN>"
```

### Wallet: Rút tiền (withdrawal)

```bash
curl -X POST http://localhost:8082/api/v1/withdrawals \
  -H "Authorization: Bearer <TOKEN>" \
  -H "Content-Type: application/json" \
  -d "{\"assetCode\": \"USDT\", \"amount\": \"50.00\", \"clientRequestId\": \"wd-001\"}"
```

### Wallet: Xem lịch sử giao dịch

```bash
curl "http://localhost:8082/api/v1/wallet-transactions?page=0&size=50" \
  -H "Authorization: Bearer <TOKEN>"
```

---

---

### Market Data: Kiểm tra health & readiness

```bash
# Liveness — service còn sống không
curl http://localhost:8085/actuator/health/liveness

# Readiness — đã load xong exchange info + backfill + WS chưa
curl http://localhost:8085/actuator/health/readiness

# Full health (bao gồm circuit breaker Binance REST)
curl http://localhost:8085/actuator/health
```

---

### Market Data — Public API (`/api/v1/marketdata`)

#### Order book (depth 20 levels)

```bash
curl "http://localhost:8085/api/v1/marketdata/orderbook/BTCUSDT"
```

**Response 200:**
```json
{
  "pair": "BTCUSDT",
  "bids": [["67234.5", "0.128"], ["67234.1", "0.5"], "..."],
  "asks": [["67235.0", "0.04"],  ["67235.8", "1.2"],  "..."],
  "updated_at": "2026-04-29T10:23:45.123Z"
}
```

Giới hạn số level qua query param `depth` (tối đa 50):

```bash
curl "http://localhost:8085/api/v1/marketdata/orderbook/ETHUSDT?depth=5"
```

#### Ticker (best bid/ask + last price)

```bash
curl "http://localhost:8085/api/v1/marketdata/ticker/BTCUSDT"
curl "http://localhost:8085/api/v1/marketdata/ticker/ETHUSDT"
curl "http://localhost:8085/api/v1/marketdata/ticker/SOLUSDT"
```

**Response 200:**
```json
{
  "pair": "BTCUSDT",
  "best_bid": "67234.5",
  "best_ask": "67235.0",
  "last_price": "67234.5",
  "updated_at": "2026-04-29T10:23:45.123Z"
}
```

#### Exchange Info (metadata tất cả các cặp)

```bash
curl "http://localhost:8085/api/v1/marketdata/exchangeInfo"
```

**Response 200:**
```json
{
  "pairs": [
    {
      "symbol": "BTCUSDT",
      "baseAsset": "BTC",
      "quoteAsset": "USDT",
      "tickSize": "0.1",
      "stepSize": "0.00001",
      "minNotional": "5",
      "pricescale": 10
    },
    "..."
  ]
}
```

---

### Market Data — Internal API (`/internal`)

> Các endpoint này dành cho Order Service và Matching Engine gọi nội bộ.

#### Ticker (internal — dùng cho Order Service)

```bash
curl "http://localhost:8085/internal/ticker/BTCUSDT"
```

#### Depth full (internal — dùng cho Matching Engine)

```bash
curl "http://localhost:8085/internal/depth/BTCUSDT"
curl "http://localhost:8085/internal/depth/BTCUSDT?levels=50"
```

#### Pair metadata (tick size, step size, min notional)

```bash
curl "http://localhost:8085/internal/pairs/BTCUSDT/metadata"
curl "http://localhost:8085/internal/pairs/ETHUSDT/metadata"
```

**Response 200:**
```json
{
  "symbol": "BTCUSDT",
  "baseAsset": "BTC",
  "quoteAsset": "USDT",
  "tickSize": "0.1",
  "stepSize": "0.00001",
  "minNotional": "5",
  "pricescale": 10
}
```

#### Feed health (trạng thái WS feed từng cặp)

```bash
curl "http://localhost:8085/internal/market-data/health"
```

**Response 200:**
```json
{
  "wsConnected": true,
  "pairs": {
    "BTCUSDT": "HEALTHY",
    "ETHUSDT": "HEALTHY",
    "BNBUSDT": "HEALTHY",
    "SOLUSDT": "HEALTHY",
    "XRPUSDT": "HEALTHY"
  }
}
```

Các trạng thái có thể: `HEALTHY` → `STALE` → `DEGRADED` → `DISCONNECTED`.

---

### Market Data — TradingView UDF (`/udf`)

> Các endpoint này theo chuẩn [TradingView UDF protocol](https://www.tradingview.com/charting-library-docs/latest/connecting_data/UDF/) để tích hợp chart.

#### Config

```bash
curl "http://localhost:8085/udf/config"
```

#### Symbol info

```bash
curl "http://localhost:8085/udf/symbols?symbol=BTCUSDT"
curl "http://localhost:8085/udf/symbols?symbol=ETHUSDT"
```

**Response 200:**
```json
{
  "name": "BTCUSDT",
  "description": "BTCUSDT",
  "type": "crypto",
  "session": "24x7",
  "exchange": "Haizz",
  "timezone": "UTC",
  "pricescale": 10,
  "minmov": 1,
  "has_intraday": true,
  "has_daily": true,
  "supported_resolutions": ["1","5","15","60","240","1D"]
}
```

#### Candlestick history

> `resolution`: `1` `5` `15` `60` `240` `1D` — `from`/`to` là UNIX timestamp (giây).

```bash
# 1-minute candles — 1 giờ gần nhất
FROM=$(date -d "1 hour ago" +%s 2>/dev/null || gdate -d "1 hour ago" +%s)
TO=$(date +%s)
curl "http://localhost:8085/udf/history?symbol=BTCUSDT&resolution=1&from=${FROM}&to=${TO}"

# 1-hour candles — 7 ngày gần nhất
FROM=$(date -d "7 days ago" +%s 2>/dev/null || gdate -d "7 days ago" +%s)
TO=$(date +%s)
curl "http://localhost:8085/udf/history?symbol=BTCUSDT&resolution=60&from=${FROM}&to=${TO}"

# Daily candles — 30 ngày gần nhất
FROM=$(date -d "30 days ago" +%s 2>/dev/null || gdate -d "30 days ago" +%s)
TO=$(date +%s)
curl "http://localhost:8085/udf/history?symbol=ETHUSDT&resolution=1D&from=${FROM}&to=${TO}"
```

**Response 200 (có dữ liệu):**
```json
{
  "s": "ok",
  "t": [1714384800, 1714388400, "..."],
  "o": ["67100.0", "67234.5", "..."],
  "h": ["67350.0", "67400.0", "..."],
  "l": ["67050.0", "67180.0", "..."],
  "c": ["67234.5", "67310.0", "..."],
  "v": ["12.345", "8.921", "..."]
}
```

**Response khi range không có dữ liệu:**
```json
{ "s": "no_data", "nextTime": 1714384800 }
```

**Dùng `countback` để lấy N nến gần nhất (TradingView style):**
```bash
TO=$(date +%s)
curl "http://localhost:8085/udf/history?symbol=BTCUSDT&resolution=15&from=0&to=${TO}&countback=100"
```

---

## Bước 8 — Build & Package

```bash
# Build Auth Service
cd D:\Project\exchange\services\auth
.\mvnw clean package -DskipTests

# Build Wallet Service
cd D:\Project\exchange\services\wallet
.\mvnw clean package -DskipTests

# Build Market Data Service (cần exchange-common được install trước)
cd D:\Project\exchange
mvn install -pl exchange-common -am -DskipTests -q
cd services\marketdata
mvn clean package -DskipTests

# Build toàn bộ từ root (khuyên dùng)
cd D:\Project\exchange
mvn clean package -DskipTests
```

---

## Dừng infrastructure

```bash
# Dừng nhưng giữ data
docker compose stop

# Dừng và xóa containers (data vẫn còn trong volumes)
docker compose down

# Dừng và xóa luôn data (reset hoàn toàn)
docker compose down -v
```

---

## Troubleshooting

| Lỗi                                                       | Nguyên nhân                                       | Fix                                                                                                    |
| --------------------------------------------------------- | ------------------------------------------------- | ------------------------------------------------------------------------------------------------------ |
| `Connection to localhost:5432 refused`                    | PostgreSQL chưa start hoặc chưa healthy           | `docker compose up -d && docker compose ps`                                                            |
| `Connection to localhost:5433 refused`                    | TimescaleDB chưa start                            | `docker compose up -d postgres-timescale`                                                              |
| `Could not find a valid Docker environment`               | Docker Desktop chưa mở                            | Mở Docker Desktop                                                                                      |
| `auth_db / wallet_db does not exist`                      | Init script chưa chạy (volumes cũ)                | `docker compose down -v && docker compose up -d`                                                       |
| Port 5432/5433/6379/9092 đã bị chiếm                    | App khác đang dùng port                           | Tắt app đó hoặc đổi port trong docker-compose.yml                                                      |
| `PASSWORD_TOO_WEAK` khi register                          | Password thiếu chữ hoa hoặc số                    | Dùng VD: `Secret1234`                                                                                  |
| `missing table [deposit_records]`                         | Migration V1 đã apply trước khi có bảng này       | Flyway V2 tự tạo lại khi restart. Hoặc: `docker compose down -v && docker compose up -d`              |
| Wallet không tạo ví sau khi register                      | Kafka chưa healthy hoặc Wallet Service chưa up    | Chờ Kafka healthy rồi start lại Wallet Service                                                         |
| `401 Unauthorized` khi gọi Wallet API                     | Token hết hạn hoặc sai                            | Đăng nhập lại lấy token mới                                                                            |
| Market Data `/actuator/health/readiness` trả về `DOWN`   | Backfill chưa xong hoặc WS chưa connect           | Xem log, chờ thêm (backfill lần đầu mất 1–5 phút)                                                     |
| `PairNotSupportedException` khi gọi ticker/depth          | Pair không có trong `market.pairs` config          | Chỉ dùng: `BTCUSDT`, `ETHUSDT`, `BNBUSDT`, `SOLUSDT`, `XRPUSDT`                                       |
| UDF `/udf/history` trả `{"s":"no_data"}`                  | Range yêu cầu nằm ngoài dữ liệu đã backfill        | Dùng range trong 30 ngày gần nhất (1m) hoặc 365 ngày (1d)                                             |
| `Circuit breaker OPEN` trong log Market Data             | Binance REST API bị lỗi liên tục                  | Chờ 30s để circuit breaker reset. Kiểm tra kết nối internet                                           |
| Binance WS disconnect liên tục                            | Network không ổn định hoặc bị block               | Service tự reconnect với backoff 1s→2s→4s→...60s. Xem log để monitor                                  |

---

## Cấu trúc kết nối

```
Auth Service (port 8081)
    ├── PostgreSQL :5432      →  auth_db
    ├── Redis :6379           →  rate limiting
    └── Kafka :9092           →  publish  user.events.v1

Wallet Service (port 8082)
    ├── PostgreSQL :5432      →  wallet_db
    └── Kafka :9092           →  consume  user.events.v1
                              →  consume  trade.executed
                              →  publish  wallet.transactions.v1

Market Data Service (port 8085)
    ├── TimescaleDB :5433     →  marketdata_db  (candlesticks hypertable)
    ├── Redis :6379           →  depth cache (TTL 5s)
    │                         →  ticker cache (TTL 10s)
    │                         →  exchangeInfo cache (TTL 25h)
    │                         →  WS status key
    ├── Kafka :9092           →  publish  market-data.events.v1   (durable, via outbox)
    │                         →  publish  market-data.depth.v1    (ephemeral)
    │                         →  publish  market-data.kline.v1    (ephemeral)
    └── Binance API           →  REST: exchange info, kline backfill
                              →  WebSocket: trade + depth20 + kline streams
```
