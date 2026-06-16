# Getting Started — Haizz Exchange

## Yêu cầu

| Tool           | Phiên bản |
|----------------|-----------|
| JDK            | 21        |
| Maven          | 3.9.15    |
| Node.js        | ≥ 18 (cho frontend) |
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

| Container            | Image                           | Port | Dùng cho                             |
|----------------------|---------------------------------|------|--------------------------------------|
| `exchange-postgres`  | postgres:15-alpine              | 5432 | Auth, Wallet, Order, Match DB        |
| `exchange-timescale` | timescale/timescaledb:2.14-pg15 | 5433 | Market Data DB (OHLCV time-series)   |
| `exchange-redis`     | redis:7-alpine                  | 6379 | Cache (depth, ticker, exchange info) |
| `exchange-kafka`     | apache/kafka:latest             | 9092 | Event bus                            |

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

## Cách nhanh nhất — chạy tất cả service (`start-all.ps1`)

Sau khi infra (Bước 2) đã healthy, mở **7 tab** service trong Windows Terminal bằng một lệnh:

```ps
cd D:\Project\exchange
pwsh -ExecutionPolicy Bypass -File .\start-all.ps1
```

Script mở: **Auth (8081), Wallet (8082), Order (8083), Gateway (8080), MarketData (8085), Matching (8084), Frontend (3000)** — mỗi service 1 tab, profile `dev`. Lần đầu mỗi Java service compile ~20–40s; MarketData cần thêm 1–5 phút backfill.

> Toàn bộ luồng end-to-end đi qua **API Gateway `:8080`** (FE chỉ gọi `:8080`). Các bước chạy từng service bên dưới dành cho khi cần chạy/đdebug riêng lẻ.

| Service | Port | Phụ thuộc khi khởi động |
|---------|------|------------------------|
| Auth | 8081 | postgres, kafka |
| Wallet | 8082 | postgres, kafka (nghe `user.events.v1`, `trade.executed`) |
| Order | 8083 | postgres (`order_db`), kafka, **Wallet** (freeze), **MarketData** (ticker cho MARKET) |
| Matching | 8084 | postgres (`match_db`), kafka, **MarketData** (depth/health), **Order** (`/internal/orders` rebuild lúc start) |
| MarketData | 8085 | timescale, redis, kafka, internet (Binance) |
| Gateway | 8080 | redis, kafka; route tới các service trên |
| Frontend | 3000 | Gateway `:8080` |

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

## Bước 4.5 — Chạy Order Service (port 8083)

Order Service cần **Wallet Service** (gọi freeze số dư khi đặt lệnh) và **Market Data Service** (lấy best ask cho lệnh MARKET). DB riêng `order_db`.

```ps
cd D:\Project\exchange\services\order
$env:SPRING_PROFILES_ACTIVE="dev"
mvn spring-boot:run
```

Start thành công khi thấy `Started OrderApplication ... Tomcat started on port 8083`.

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

## Bước 5.5 — Chạy Matching Engine (port 8084)

Matching Engine mô phỏng khớp lệnh theo dữ liệu Binance (không P2P). Cần **Market Data** (depth + feed health), **Order Service** (nạp lệnh OPEN lúc startup qua `/api/v1/orders/internal/orders`), kafka. DB riêng `match_db`.

```ps
cd D:\Project\exchange\services\matching
$env:SPRING_PROFILES_ACTIVE="dev"
mvn spring-boot:run
```

Start thành công khi thấy `Started MatchingApplication ... Tomcat started on port 8084`.

> Luồng: đặt lệnh (Order) → `orders.events.v1` → Matching khớp theo Binance → `trade.executed` (Wallet ghi sổ) + `matching.events.v1` (Order cập nhật state + FE nhận realtime qua WS Gateway channel `orders`).

---

## Bước 6 — Chạy Frontend (port 3000)

Frontend là app Next.js (`services/frontend`, package `haizz-trading-panel`) — App Router + TypeScript + Tailwind (prefix `hx-`).

### Cài đặt & chạy

```ps
cd D:\Project\exchange\services\frontend
npm install          # chỉ lần đầu
copy .env.example .env.local
npm run dev
```

Mở http://localhost:3000

### API Gateway

Frontend gọi **một API Gateway duy nhất** qua `NEXT_PUBLIC_GATEWAY_URL` (mặc định `http://localhost:8080`). Gateway **đã được build** và gom REST + WS của mọi service về `:8080` — chạy Gateway (`services/gateway`) cùng các service backend là FE hoạt động đầy đủ.

Trạng thái tính năng: **Auth / Wallet / Chart / OrderBook / TradesTape** đã wired thật qua Gateway. **OrderForm-submit + thông báo khớp lệnh** vẫn đang stub ở FE (backend Order/Matching đã sẵn sàng — chỉ còn nối FE).

---

## Bước 7 — Cấu hình VS Code (chạy các Java service)

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

## Bước 8 — Test API
### Auth
#### Đăng ký tài khoản

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

#### Đăng nhập — lấy token

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

#### Lấy thông tin user

```bash
curl http://localhost:8081/auth/me \
  -H "Authorization: Bearer <TOKEN>"
```

#### Refresh token

```bash
curl -X POST http://localhost:8081/auth/refresh \
  -H "Content-Type: application/json" \
  -d "{\"refresh_token\": \"<refresh_token>\"}"
```

#### Logout

```bash
curl -X POST http://localhost:8081/auth/logout \
  -H "Authorization: Bearer <TOKEN>" \
  -H "Content-Type: application/json" \
  -d "{\"refresh_token\": \"<refresh_token>\"}"
```

#### Validate token (internal — dành cho Gateway)

```bash
curl -X POST http://localhost:8081/internal/auth/validate-token \
  -H "Content-Type: application/json" \
  -d "{\"token\": \"<TOKEN>\"}"
```

---

### Wallet
#### Xem ví của tôi

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

#### Nạp tiền (deposit)

```bash
curl -X POST http://localhost:8082/api/v1/deposits \
  -H "Authorization: Bearer <TOKEN>" \
  -H "Content-Type: application/json" \
  -d "{\"assetCode\": \"USDT\", \"amount\": \"100.00\", \"clientRequestId\": \"dep-001\"}"
```

#### Xem lịch sử nạp tiền

```bash
curl "http://localhost:8082/api/v1/deposits?page=0&size=20" \
  -H "Authorization: Bearer <TOKEN>"
```

#### Rút tiền (withdrawal)

```bash
curl -X POST http://localhost:8082/api/v1/withdrawals \
  -H "Authorization: Bearer <TOKEN>" \
  -H "Content-Type: application/json" \
  -d "{\"assetCode\": \"USDT\", \"amount\": \"50.00\", \"clientRequestId\": \"wd-001\"}"
```

#### Xem lịch sử giao dịch

```bash
curl "http://localhost:8082/api/v1/wallet-transactions?page=0&size=50" \
  -H "Authorization: Bearer <TOKEN>"
```

---

### Order (qua Gateway `:8080`)

> Cần nạp USDT (deposit) trước để có số dư khả dụng cho lệnh BUY.

#### Đặt lệnh LIMIT BUY

```bash
curl -X POST http://localhost:8080/api/v1/orders \
  -H "Authorization: Bearer <TOKEN>" \
  -H "Content-Type: application/json" \
  -d "{\"pair\":\"BTCUSDT\",\"side\":\"BUY\",\"type\":\"LIMIT\",\"quantity\":\"0.001\",\"limit_price\":\"50000\",\"client_order_id\":\"ord-001\"}"
```

**Response 201:** order `state=NEW`, `freeze_amount`/`freeze_asset` đã tính; số dư USDT chuyển sang `frozen` (xem lại `/api/v1/wallets/me`).

#### Đặt lệnh MARKET BUY

```bash
curl -X POST http://localhost:8080/api/v1/orders \
  -H "Authorization: Bearer <TOKEN>" \
  -H "Content-Type: application/json" \
  -d "{\"pair\":\"BTCUSDT\",\"side\":\"BUY\",\"type\":\"MARKET\",\"quantity\":\"0.001\"}"
```

#### Xem 1 lệnh / danh sách lệnh

```bash
curl http://localhost:8080/api/v1/orders/<ORDER_ID> -H "Authorization: Bearer <TOKEN>"
curl "http://localhost:8080/api/v1/orders?pair=BTCUSDT&state=NEW,OPEN,PARTIALLY_FILLED&page=0&size=50" \
  -H "Authorization: Bearer <TOKEN>"
```

#### Hủy lệnh (chỉ NEW / OPEN / PARTIALLY_FILLED)

```bash
curl -X DELETE http://localhost:8080/api/v1/orders/<ORDER_ID> -H "Authorization: Bearer <TOKEN>"
```

→ `state=CANCEL_REQUESTED`, phần freeze còn lại được hoàn về `available`.

---

### Trades (Matching Engine, qua Gateway `:8080`)

```bash
curl "http://localhost:8080/api/v1/trades?page=0&size=50" -H "Authorization: Bearer <TOKEN>"
```

Mỗi fill tạo 1 `Trade` (giá, qty, fee taker 0.10%, role TAKER). Khi lệnh khớp: Wallet trừ `frozen` + cộng `available` (trừ fee), Order cập nhật `filled_qty`/`state`.

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
curl "http://localhost:8085/internal/depth/BTCUSDT?depth=50"
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

## Bước 9 — Build & Package

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

| Lỗi                                                    | Nguyên nhân                                    | Fix                                                                                      |
|--------------------------------------------------------|------------------------------------------------|------------------------------------------------------------------------------------------|
| `Connection to localhost:5432 refused`                 | PostgreSQL chưa start hoặc chưa healthy        | `docker compose up -d && docker compose ps`                                              |
| `Connection to localhost:5433 refused`                 | TimescaleDB chưa start                         | `docker compose up -d postgres-timescale`                                                |
| `Could not find a valid Docker environment`            | Docker Desktop chưa mở                         | Mở Docker Desktop                                                                        |
| `auth_db / wallet_db does not exist`                   | Init script chưa chạy (volumes cũ)             | `docker compose down -v && docker compose up -d`                                         |
| Port 5432/5433/6379/9092 đã bị chiếm                   | App khác đang dùng port                        | Tắt app đó hoặc đổi port trong docker-compose.yml                                        |
| `PASSWORD_TOO_WEAK` khi register                       | Password thiếu chữ hoa hoặc số                 | Dùng VD: `Secret1234`                                                                    |
| `missing table [deposit_records]`                      | Migration V1 đã apply trước khi có bảng này    | Flyway V2 tự tạo lại khi restart. Hoặc: `docker compose down -v && docker compose up -d` |
| Wallet không tạo ví sau khi register                   | Kafka chưa healthy hoặc Wallet Service chưa up | Chờ Kafka healthy rồi start lại Wallet Service                                           |
| `401 Unauthorized` khi gọi Wallet API                  | Token hết hạn hoặc sai                         | Đăng nhập lại lấy token mới                                                              |
| Market Data `/actuator/health/readiness` trả về `DOWN` | Backfill chưa xong hoặc WS chưa connect        | Xem log, chờ thêm (backfill lần đầu mất 1–5 phút)                                        |
| `PairNotSupportedException` khi gọi ticker/depth       | Pair không có trong `market.pairs` config      | Chỉ dùng: `BTCUSDT`, `ETHUSDT`, `BNBUSDT`, `SOLUSDT`, `XRPUSDT`                          |
| UDF `/udf/history` trả `{"s":"no_data"}`               | Range yêu cầu nằm ngoài dữ liệu đã backfill    | Dùng range trong 30 ngày gần nhất (1m) hoặc 365 ngày (1d)                                |
| `Circuit breaker OPEN` trong log Market Data           | Binance REST API bị lỗi liên tục               | Chờ 30s để circuit breaker reset. Kiểm tra kết nối internet                              |
| Binance WS disconnect liên tục                         | Network không ổn định hoặc bị block            | Service tự reconnect với backoff 1s→2s→4s→...60s. Xem log để monitor                     |

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

Order Service (port 8083)
    ├── PostgreSQL :5432      →  order_db
    ├── HTTP                  →  Wallet  /api/v1/wallets/internal/freeze|unfreeze
    │                         →  MarketData  /internal/ticker (freeze lệnh MARKET)
    └── Kafka :9092           →  publish  orders.events.v1   (EventEnvelope, via outbox)
                              →  consume  matching.events.v1 (áp fill + release residual)

Matching Engine (port 8084)
    ├── PostgreSQL :5432      →  match_db  (trades, matching_outbox)
    ├── HTTP                  →  MarketData  /internal/depth, /internal/market-data/health
    │                         →  Order  /api/v1/orders/internal/orders (rebuild index lúc start)
    └── Kafka :9092           →  consume  orders.events.v1
                              →  consume  market-data.events.v1 (external trade + feed health)
                              →  publish  trade.executed       (cho Wallet ghi sổ)
                              →  publish  matching.events.v1   (Order state + WS Gateway)

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

API + WS Gateway (port 8080)
    ├── Redis :6379           →  rate limiting (token-bucket Lua), WS subscriptions
    ├── HTTP route            →  auth/wallet/order/marketdata/trades theo path prefix
    │                            (validate JWT, inject X-User-Id/Email/Roles)
    └── Kafka :9092           →  WS fan-out: matching.events.v1→orders,
                                  wallet.transactions.v1→wallet, market-data.*→price

Frontend (port 3000)
    └── API Gateway :8080     →  gom REST + WS của mọi service (NEXT_PUBLIC_GATEWAY_URL)
```
