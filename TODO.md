# TODO — Tiến độ coding (mức tính năng)

> Cập nhật: 2026-06-16. Đối chiếu với `docs/SRS.md §3` (Functional Requirements).
> Legend: ✅ xong · 🟡 đang làm / một phần · ⬜ chưa làm · 🧊 hoãn (post-MVP / Stage 2)

## Tổng quan theo service

| Service | Port | Trạng thái | Ghi chú |
|---------|------|-----------|---------|
| Auth | 8081 | ✅ Xong | SSO delegation (Stage 2) hoãn |
| Wallet | 8082 | ✅ Xong | |
| Market Data | 8085 | 🟡 Gần xong | Code đủ tính năng, **thiếu test thật** |
| Frontend | 3000 | 🟡 Scaffold | Auth/Wallet/Chart/OrderBook/TradesTape wired qua Gateway; OrderForm-submit + khớp lệnh còn stub (chờ Order/Matching) |
| Order | 8083 | 🟡 Gần xong | Place/cancel/get/list + `/internal/orders` + outbox + state machine xong; consumer khớp lệnh (`matching.events.v1`) còn **stub** chờ Matching Engine |
| Matching Engine | 8084 | ⬜ Chưa làm | Chưa có thư mục service (Gateway đã để sẵn route + mapping WS) |
| API + WS Gateway | 8080 | ✅ Xong | HS256 dev; route+JWT+rate-limit+WS fan-out; build xanh, 13 unit test. live-balance còn nửa-stub (wallet phát delta) |
| Hạ tầng (Docker) | — | ✅ Xong | postgres, timescale, redis, kafka |

---

## 3.1 User & Auth — ✅

- [x] Đăng ký email + password, hash bcrypt/argon2 (SR-001, SR-002, SR-003)
- [x] Login → JWT access (60m) + refresh (7d) (SR-004, SR-005)
- [x] Logout — vô hiệu refresh token (SR-007)
- [x] Rate-limit login (10 fails/10m → 429)
- [x] Internal validate-token (cho Gateway)
- [x] Publish `user.events.v1` (outbox)
- [ ] 🧊 Delegate auth sang OAuth2/OIDC host app (SR-006, Stage 2)

## 3.2 Wallet — ✅

- [x] Tạo wallet mỗi asset khi đăng ký + grant 10.000 USDT (SR-010, SR-011)
- [x] Balance total/available/frozen (SR-012)
- [x] Deposit USDT (≤100k/tx, instant, chỉ USDT) (SR-013–015)
- [x] Withdraw theo available (SR-016–018)
- [x] WalletTransaction bất biến + lịch sử phân trang (SR-019, SR-022)
- [x] Read endpoint `/wallets/me` (SR-021)
- [x] Freeze/unfreeze cho Order Service (SR-035 phía wallet)
- [x] Optimistic lock chống lost update (SR-023)
- [x] Consume `trade.executed`, publish `wallet.transactions.v1`

## 3.3 Order Management — 🟡 (gần xong; consumer khớp lệnh còn stub)

- [x] Market order: quantity / quoteOrderQty (SR-030)
- [x] Limit order: price + quantity, TIF=GTC (SR-031, SR-032)
- [x] Validate minNotional / tickSize / stepSize (SR-033) — `domain/OrderValidator`
- [x] Check available balance + freeze khi đặt lệnh (SR-034, SR-035) — `domain/FreezeCalculator` + sync call Wallet
- [x] orderId UUID + clientOrderId idempotency (SR-036, SR-037)
- [x] Cancel lệnh NEW / PARTIALLY_FILLED (SR-038, SR-039)
- [x] Publish `OrderPlaced` / `OrderCancelled` (SR-040) — outbox relay
- [x] Read endpoint orders (filter status/pair/date) + `/internal/orders` (SR-041)
- [x] Order state machine (NEW→OPEN→PARTIALLY_FILLED→FILLED / CANCELLED / REJECTED, terminal-precedence) + unit test (SR-042)
- [ ] 🟡 Consume `matching.events.v1` để cập nhật filled qty + state — **stub** (`OrderEventConsumer` + `ProcessFillEventUseCase`, log+TODO), hoàn thiện cùng Matching Engine

## 3.4 Matching Engine (Simulation) — ⬜ (chưa bắt đầu)

- [ ] Match theo Binance data (không P2P) (SR-050)
- [ ] Market fill tại best bid/ask + slippage 0.05% (SR-051)
- [ ] Walk-the-book + VWAP khi vượt depth (SR-052)
- [ ] Limit eligible khi lastPrice chạm limit (SR-053)
- [ ] Partial fill theo volume external, cửa sổ 5s (SR-054)
- [ ] Tạo `Trade` record mỗi fill (SR-055)
- [ ] Publish `TradeExecuted` (SR-056)
- [ ] Tính fee taker/maker 0.10% + đúng asset (SR-057, SR-058)
- [ ] Pause matching khi feed mất >30s, reject market order (SR-059)

## 3.5 Market Data — 🟡 (gần xong)

- [x] Backfill OHLCV từ Binance REST, 5 cặp × 6 interval (SR-060)
- [x] Lưu TimescaleDB hypertable theo openTime (SR-061)
- [x] Subscribe Binance WS `@trade` + `@depth`, publish Kafka (SR-062)
- [x] UDF `/udf/config|symbols|history` (SR-063)
- [x] `/api/v1/marketdata/orderbook/{pair}?depth=` (SR-064)
- [x] Cache exchangeInfo (Redis) + `/exchangeInfo` endpoint (SR-065, SR-066)
- [x] WS reconnect backoff (SR-068)
- [x] Cache ticker (best bid/ask/last) TTL 10s (SR-069)
- [x] Feed health per-pair + degraded events
- [ ] 🟡 WS `@kline_1m` live (hiện dùng REST poll — đủ cho MVP) (SR-062/SR-067)
- [ ] ⬜ **Unit/integration test** (mapper, ingestion, UDF, health, backfill) — SRS §13
- [ ] 🧊 Continuous aggregates / compression (post-MVP)

## 3.6 Frontend — 🟡 (scaffold xong, chưa tích hợp full)

- [x] Next.js + React 18, Tailwind (prefix `hx-`) (SR-070)
- [x] Entry embeddable single-mount `panel/` (SR-071)
- [x] TradingView Lightweight Charts v5 wired vào `/udf` (SR-072)
- [x] Khung đủ màn hình: Login/Register, Wallet, Trade, Orders, Trades, Deposit (SR-073)
- [x] Auth standalone + AuthBridge (Stage 2 token) scaffolded (SR-075)
- [x] WsClient (multiplex, reconnect) — Gateway đã cấp data real-time (SR-074)
- [x] 🟢 OrderBook / TradesTape / live-chart — đã có data qua WS Gateway (depth/trade/kline)
  - 2026-06-16: fix render chart (StrictMode tạo trùng → 2 logo; chart trống khi quay lại pair đã cache) + Toast SSR portal — ở branch `feat/fe-market-data`, **chưa merge vào main**
- [ ] 🟡 OrderForm-submit + thông báo khớp lệnh — vẫn **stub** (cần Order + Matching service)
- [ ] 🟡 Live-balance: WS có chạy nhưng wallet chỉ phát delta → chưa cập nhật số dư đầy đủ (xem §3.7)
- [ ] 🟡 Wallet/Orders/Trades tables — gọi API thật, chờ backend tương ứng
- [ ] ⬜ Responsive polish ≥1024px (SR-076)
- [ ] 🧊 Build library bundle (rollup) cho Stage 2 embed

## 3.7 API Gateway & WebSocket Gateway — ✅ (xong, `services/gateway` :8080)

> Nút thắt đã mở: FE gọi backend qua một base URL `:8080`, có real-time fan-out.
> Build xanh, 13 unit test (JwtVerifier + WsMessageRouter). Spring Cloud Gateway 2025.0.1 / Boot 4.0.6.

- [x] API Gateway (Spring Cloud Gateway) route theo path prefix (SR-080) — auth/wallet/order/matching/marketdata; order+matching để sẵn route (503 tới khi có service)
- [x] Validate JWT + inject userId/roles header (SR-081) — **HS256 + shared secret** (auth RS256 sinh key ephemeral nên không validate cục bộ được); inject `X-User-Id/Email/Roles`
- [x] Rate-limit per-user 60 rps / 120 burst + per-IP 120/240 (SR-082) — Redis token-bucket Lua
- [x] WS Gateway multiplex price/order/wallet, max 5 conn/user (SR-083) — reactive WebFlux, Sinks per-session
- [x] WS auth qua JWT handshake (`?token=`) + đóng 4401 khi token hết hạn (SR-084)
- [ ] 🟡 Fan-out `wallet` live-balance: wallet phát **delta** nhưng FE cần số dư tuyệt đối → cần back-port (wallet thêm absolute balance HOẶC FE refetch). Xem `services/gateway/DECISIONS.md`
- [ ] 🧊 RS256 + JWKS cho prod; circuit breaker per-route; multi-instance Redis pub/sub bridge (post-MVP)
- [ ] ⬜ Integration test (routing qua WireMock, WS handshake/subscribe, Kafka fan-out) — chưa viết

---

## Đường tới hệ thống chạy end-to-end

Thứ tự đề xuất (mỗi mục mở khoá mục sau):

1. ✅ **API + WS Gateway** — gom REST + real-time. FE chạy được auth + wallet + chart + orderbook + tradestape qua `:8080`.
2. 🟡 **Order Service** — đặt/hủy/đọc lệnh + freeze qua Wallet + outbox + state machine **xong**; còn consumer `matching.events.v1` (stub) hoàn thiện cùng Matching. Gateway đã để sẵn route.
3. ⬜ **Matching Engine** — fill theo Market Data (đã sẵn sàng cấp data) → `TradeExecuted` → Wallet/Order cập nhật. Gateway đã chừa mapping WS `matching.events.v1` → channel `orders`.
4. 🟡 Hoàn thiện FE (bỏ stub OrderForm-submit + thông báo khớp lệnh) sau khi (2)(3) xong.
5. 🟡 Back-port wallet event để live-balance đủ (absolute balance) + viết integration test cho Gateway.
6. 🟡 Viết test cho Market Data trước khi coi là "done".

Trạng thái hiện tại: **3/4 service lõi xong (auth, wallet, gateway)** + **market-data gần xong** + **FE đã chạy phần read/real-time**. Còn lại order, matching.

---

## 🎯 Bước tiếp theo: Order Service (`services/order`, :8083)

> Spec đầy đủ: `docs/API_SPEC.md §3` (place/cancel/get/list + `/internal/orders`) và `docs/SRS.md §3.3` (SR-030→042).
> Template cấu trúc: copy layout `services/wallet` (hexagonal: `api / application / domain / infrastructure`, outbox pattern, Flyway migration). Build chung Spring Boot 4 / Java như wallet.

**Tích hợp đã sẵn sàng (không phải dựng lại):**
- Gateway đã chừa route `/api/v1/orders/**` → hiện trả 503, sẽ hoạt động khi service lên :8083; header `X-User-Id/Email/Roles` đã được inject.
- Wallet đã có `POST /internal/wallets/freeze` + `/unfreeze` (idempotent theo `referenceType=ORDER, referenceId`) — xem `docs/API_SPEC.md` §Freeze/Unfreeze.
- Market Data cấp best bid/ask qua ticker (cho freeze amount của MARKET order).

**Việc cần làm (đề xuất thứ tự):**
1. ✅ Scaffold service: pom/Dockerfile theo wallet, package `com.haizz.exchange.order`, port 8083, Flyway migration bảng `orders` + `order_outbox`.
2. ✅ Domain: `Order` aggregate + state machine (NEW→OPEN→PARTIALLY_FILLED→FILLED / CANCELLED / REJECTED, terminal-precedence) theo §3.4 spec.
3. ✅ Place order (`POST /api/v1/orders`): validate type/price (MARKET vs LIMIT), business rules (tickSize/stepSize/minNotional, ≤100 open orders/pair), idempotency `client_order_id` (24h), tính freeze amount, **sync call Wallet freeze**, persist Order(NEW)+outbox trong 1 transaction.
4. ✅ Cancel (`DELETE /api/v1/orders/{id}`) — chỉ NEW/PARTIALLY_FILLED; unfreeze phần còn lại.
5. ✅ Get + List orders (filter state/pair/date, phân trang) — §3.3/§3.4.
6. ✅ `/internal/orders` projection (cho Matching Engine startup nạp lệnh OPEN).
7. ✅ Outbox relay publish `OrderPlaced` / `OrderCancelled` (Kafka), tái dùng pattern outbox của wallet.
8. 🟡 Consume khớp lệnh (`matching.events.v1`) để cập nhật filled qty + state — **stub** (`OrderEventConsumer` deserialize EventEnvelope + switch eventType → `ProcessFillEventUseCase` log+TODO, fail-soft); hoàn thiện cùng Matching.
9. ✅ Unit test state machine + validation + freeze calc (31 test, pure-unit, không cần Docker).

**Lưu ý quyết định:** ghi các judgment-call không có trong spec vào `services/order/DECISIONS.md` (theo convention các service khác).
