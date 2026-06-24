# Glossary — Khái niệm Event-Driven & Messaging

**Project:** Simulated Crypto Trading Platform — *Haizz Exchange*
**Mục đích:** Giải thích nhanh các khái niệm hạ tầng message (Kafka/outbox) dùng xuyên suốt dự án, kèm cách tra cứu & tự chẩn đoán khi "lệnh không khớp / event không tới". Tài liệu kiến trúc đầy đủ xem [`SystemDesign.md`](SystemDesign.md).

> Ghi chú bối cảnh: viết ra sau khi debug sự cố "open order không match được lệnh" (2026-06-24) — chuỗi 3 lỗi: consumer Matching chết → outbox Market Data tồn đọng 6 ngày → consumer Order chết. Phần [§5 Tự chẩn đoán](#5-tu-chan-doan-khi-event-khong-toi) chính là quy trình đã dùng để tìm ra.

---

## 1. Outbox (hộp thư đi)

Khi một service muốn phát event nhưng **không gửi thẳng lên Kafka** (sợ Kafka chết lúc đó là mất event), nó ghi event vào **một bảng DB** trước cho chắc, trong **cùng transaction** với việc lưu dữ liệu nghiệp vụ. Một tiến trình nền (xem [Relay](#3-relay-bo-bom-outbox-kafka)) sau đó đọc bảng này và gửi dần lên Kafka.

- Đây là mẫu **Transactional Outbox** → đảm bảo "lưu DB và phát event" là *nguyên tử*: không bao giờ lưu mà quên phát, cũng không phát mà chưa lưu (tránh mất/nhân đôi event).
- Mỗi dòng outbox có `published_at`: `NULL` = chưa gửi, có giá trị = đã gửi xong.

**Trong dự án này:**
- Market Data: bảng `market_data_outbox` — DDL ở [`V3__create_market_data_outbox.sql`](../services/marketdata/src/main/resources/db/migration/V3__create_market_data_outbox.sql), ghi bởi [`TradeIngestionService`](../services/marketdata/src/main/java/com/haizz/exchange/marketdata/application/ingestion/TradeIngestionService.java).
- Order: `order_outbox`; Matching: `matching_outbox`; Auth, Wallet tương tự. Đây là pattern dùng chung toàn hệ thống.

---

## 2. Backlog (hàng tồn chưa xử lý)

**Backlog** = lượng việc đã xếp hàng nhưng **chưa được xử lý**. Xuất hiện ở 2 nơi:

- **Backlog outbox:** số dòng `published_at IS NULL` — đã ghi vào bảng outbox nhưng relay chưa kịp gửi lên Kafka. Nếu tốc độ ghi (vd Binance bơm trade) > tốc độ relay gửi → backlog phình to và relay bị **trễ** (đang gửi dữ liệu của nhiều giờ/ngày trước).
- **Backlog consumer (= Lag):** xem [§4](#4-consumer-group-lag).

**Vì sao nguy hiểm:** relay gửi theo thứ tự **cũ → mới (FIFO)**. Backlog lớn nghĩa là Kafka đang nhận **giá CŨ**, khiến matching khớp lệnh theo giá lỗi thời thay vì giá hiện tại.

**Cách đo (Market Data outbox):**
```sql
SELECT count(*) FILTER (WHERE published_at IS NULL) AS unpublished,
       min(created_at) FILTER (WHERE published_at IS NULL) AS oldest_unpublished,
       now()
FROM market_data_outbox;
```
Nếu `oldest_unpublished` cách `now()` nhiều phút/giờ → relay đang trễ.

---

## 3. Relay (bộ bơm Outbox → Kafka)

Tiến trình nền chạy theo lịch (vd mỗi 100ms), làm vòng lặp:
1. `SELECT` các dòng outbox `published_at IS NULL` cũ nhất (theo lô — *batch*, vd 100 dòng).
2. Gửi từng dòng lên Kafka.
3. Đánh dấu `published_at = now()` cho dòng đã gửi.

Throughput relay ≈ `batch_size / interval`. Nếu nhỏ hơn tốc độ sinh event → backlog tăng dần.

**Trong dự án này:** [`MarketDataOutboxRelay`](../services/marketdata/src/main/java/com/haizz/exchange/marketdata/infrastructure/outbox/MarketDataOutboxRelay.java) (Market Data), `OrderOutboxRelay`, `MatchingOutboxRelay`… Cấu hình `relay-fixed-delay-ms` / `relay-batch-size` trong `application.yml` mỗi service.

---

## 4. Consumer Group & Lag

- **Topic:** một "hàng đợi" message trong Kafka (vd `market-data.events.v1`, `orders.events.v1`, `matching.events.v1`, `trade.executed`).
- **Consumer group:** một nhóm tiến trình cùng đọc một topic; mỗi service là một group (`matching-engine`, `order-service`, `wallet-service`). Kafka nhớ group đã đọc tới đâu bằng **offset đã commit**.
- **Kafka tail (đuôi topic):** vị trí message **mới nhất** vừa được ghi vào (`LOG-END-OFFSET`). Consumer khỏe sẽ bám sát tail.
- **Lag:** `LOG-END-OFFSET − CURRENT-OFFSET` = số message group **chưa đọc**. Lag ≈ 0 → real-time. Lag lớn và **không giảm** → consumer chậm hoặc đã chết.

**Dấu hiệu consumer CHẾT (rất quan trọng):** group ở trạng thái `STATE=Empty`, `#MEMBERS=0`, cột `CONSUMER-ID` rỗng (`-`). Khi đó dù process HTTP vẫn `health=UP`, các `@KafkaListener` đã ngừng → **không event nào được xử lý**.

**Lệnh tra cứu (chạy trong container `exchange-kafka`):**
```bash
# Đếm message từng topic
docker exec exchange-kafka bash -c \
  "/opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:9092 --topic <topic>"

# Lag + thành viên của một group
docker exec exchange-kafka bash -c \
  "/opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group <group>"

# Trạng thái nhanh (Empty/Stable, số member)
docker exec exchange-kafka bash -c \
  "/opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group <group> --state"
```
> Lưu ý Git Bash trên Windows: bọc đường dẫn `/opt/...` trong `bash -c "..."` để tránh bị mangle thành `D:/Apps/Git/opt/...`.

---

## 5. Tự chẩn đoán khi "event không tới / lệnh không khớp"

Đi từ **nguồn → đích** theo luồng [`Exchange_Flow.md`](Exchange_Flow.md):

1. **Nguồn có sinh event không?** Market Data WS Binance còn kết nối? Topic `market-data.events.v1` có tăng offset không?
2. **Outbox có bị backlog/trễ không?** Query `oldest_unpublished` (xem [§2](#2-backlog-hang-ton-chua-xu-ly)). Nếu trễ nhiều → Kafka đang nhận **giá cũ** → matching khớp sai giá. *(Lỗi đã gặp: backlog 8,4M, trễ 6 ngày.)*
3. **Consumer phía xử lý còn sống không?** `--describe --group <group> --state`. Nếu `Empty / 0 members` → consumer chết → **restart service** đó để listener join lại group. *(Đã gặp ở cả `matching-engine` lẫn `order-service`.)*
4. **Lệnh có vào index matching không?** Tìm trong log matching dòng `Added LIMIT order to index orderId=...`. Nhớ: lệnh LIMIT **chỉ khớp khi có external trade từ Binance vượt giá limit đúng phía**, không tự khớp lẫn nhau — xem [`LimitOrderMatcher`](../services/matching/src/main/java/com/haizz/exchange/matching/application/LimitOrderMatcher.java) và mục Matching trong [`SystemDesign_Appendix_MatchingEngine.md`](SystemDesign_Appendix_MatchingEngine.md).
5. **Khớp xong, đích có cập nhật không?** Matching emit `OrderFilled` lên `matching.events.v1` → Order Service (group `order-service`) phải consume mới đổi trạng thái lệnh. Nếu lệnh kẹt `NEW`/`OPEN` dù matching log đã `Limit fill ... fullyFilled=true` → kiểm tra group `order-service` (bước 3).

**Ghi nhớ vận hành:** nhiều service start bằng `mvn spring-boot:run` qua [`start-all.ps1`](../start-all.ps1) (log ra terminal, không ra file). Khi cần điều tra lý do consumer chết, restart và **redirect log ra file** để bắt stacktrace.
