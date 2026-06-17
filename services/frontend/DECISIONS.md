# Frontend — Decision Log

> Các judgment-call khi dev FE không có sẵn trong spec (SRS / SystemDesign_Appendix_Frontend / API_SPEC).
> Mục đã back-port vào tài liệu chính thức được đánh dấu ✅ (doc).

## 2026-06-17 — Luồng trade thật (đặt/hủy lệnh + realtime fills + live-balance)

### D1. WS frame thực tế là `{channel, schema, payload, timestamp}` — route theo `schema` ✅ (doc §6.2)
Gateway (`WsMessageRouter`) phát envelope `{channel, schema, payload, timestamp}`. `WsClient` route handler theo `schema`. Method đăng ký handler tên **`onSchema(schema, handler)`** (SystemDesign §6.2 viết `onMessage` — đã đổi tên trong code). `subscribe(channel)` gửi `{op:'subscribe',channels:[...]}` (đúng như doc).

### D2. `WsStoreSyncer` đặt ở `src/panel/` (không phải `src/lib/ws/`) ✅ (doc §5.5)
Vì nó import từ `features/*` (stores) mà `lib/` không được phép import (ESLint import-direction rule §2.3). Mount qua `PanelProviders` → dùng chung cho **cả** standalone (`src/app`) lẫn embedded (`src/panel`).

### D3. Subscribe `orders` + `wallet` ngay trong `WsStoreSyncer` ✅ (doc §5.5)
Khác market channels (do `OrderBook`/`TradesTape` tự subscribe theo pair), 2 channel user-scoped `orders`/`wallet` không có component "sở hữu" → `WsStoreSyncer` gọi `useWsSubscription('orders')` + `useWsSubscription('wallet')` global. Gateway lọc theo `userId` từ JWT handshake nên chỉ nhận event của chính user.

### D4. Map payload matching camelCase → `FillUpdate` snake_case + derive `state` từ schema ✅ (doc §5.5)
Gateway forward payload matching **nguyên camelCase** (`{orderId, filledQuantity, avgPrice|fillPrice, remainingQuantity, reason}`), KHÔNG có `state`. FE map:
- `OrderFilled` → `{order_id, state:'FILLED', filled_qty:filledQuantity, avg_fill_price:avgPrice}`
- `OrderPartiallyFilled` → `{order_id, state:'PARTIALLY_FILLED', filled_qty:filledQuantity, avg_fill_price:fillPrice}`
- `OrderCancelled` → `{order_id, state:'CANCELLED'}`
`state` suy ra từ schema suffix (`matching.events.v1.<eventType>`).

### D5. Bảng dùng TanStack Query → fill events phải **invalidate query**, không chỉ update store ✅ (doc §5.5)
OpenOrders/OrderHistory/TradeHistory đọc qua `useQuery` (`['orders']`/`['trades']`), không đọc Zustand. Nên mỗi matching event vừa `applyFillUpdate` (store) vừa `invalidateQueries(['orders'])` + `(['trades'])` để bảng refresh.

### D6. Live-balance = **refetch `/wallets/me`** thay vì áp delta ✅ (doc §5.5; giải quyết §3.7 "wallet phát delta")
Gateway forward `wallet.transactions.v1` dạng **delta thô** (`deltaAvailable/deltaFrozen`), không có số dư tuyệt đối. Áp delta client-side dễ lệch khi miss message/reconnect. → Khi nhận event wallet, chỉ `invalidateQueries(['wallets'])` để lấy lại số dư tuyệt đối (nguồn sự thật). Bỏ `applyBalanceChange` delta.

### D7. Toast khi fill (tái dùng Toast có sẵn)
OrderFilled→success "Lệnh đã khớp đầy đủ"; OrderPartiallyFilled→info "Lệnh khớp một phần"; OrderCancelled→warning ("Lệnh bị từ chối" nếu reason=REJECTED, ngược lại "Lệnh đã hủy").

### D8. `client_order_id` sinh ở FE (UUID) cho mỗi lần đặt lệnh — idempotency
Dùng `generateCorrelationId()` (UUID) làm `client_order_id`; MARKET **không** gửi `limit_price` (backend reject `LIMIT_PRICE_NOT_ALLOWED`).

### D9. Bug fix: `ToastProvider` phải bọc `PanelProviders` ở **cả 2 entry tree** ✅ (doc §3)
`useToast()` được gọi trong `WsStoreSyncer` (mount trong `PanelProviders`). Trước đó `src/app/providers.tsx` đặt ToastProvider lồng dưới PanelStoreProviders, còn `src/panel/HaizzTradingPanel.tsx` thiếu hẳn → `useToast()` sẽ throw. Đã đưa ToastProvider bọc trên `PanelStoreProviders` ở cả hai (dưới `QueryClientProvider`).
