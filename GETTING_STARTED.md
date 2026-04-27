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

Lệnh này sẽ tải image và khởi động 3 container:

| Container           | Image               | Port | Dùng cho             |
| ------------------- | ------------------- | ---- | -------------------- |
| `exchange-postgres` | postgres:15-alpine  | 5432 | Database             |
| `exchange-redis`    | redis:7-alpine      | 6379 | Rate limiting, cache |
| `exchange-kafka`    | apache/kafka:latest | 9092 | Event bus            |

### Kiểm tra containers đã healthy chưa

```bash
docker compose ps
```

Chờ đến khi cột `STATUS` hiện `healthy` cho cả 3 container (khoảng 30–60 giây lần đầu do phải pull image).

```
NAME                STATUS
exchange-postgres   Up X seconds (healthy)
exchange-redis      Up X seconds (healthy)
exchange-kafka      Up X seconds (healthy)
```

### Kiểm tra database đã được tạo chưa

```bash
docker exec exchange-postgres psql -U postgres -l
```

Phải thấy: `auth_db`, `wallet_db`, `order_db`, `match_db` trong danh sách.

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

Xem cấu hình launch.json ở Bước 5.

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

Xem cấu hình launch.json ở Bước 5.

### Service đã start thành công khi thấy log:

```
Started WalletApplication in X.XXX seconds
Tomcat started on port 8082
```

---

## Bước 5 — Cấu hình VS Code (chạy cả hai service)

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
    }
  ],
  "compounds": [
    {
      "name": "All Services",
      "configurations": ["Auth Service", "Wallet Service"]
    }
  ]
}
```

Nhấn **F5** hoặc vào tab **Run and Debug** (Ctrl+Shift+D) → chọn **Auth Service**, **Wallet Service**, hoặc **All Services**.

---

## Bước 6 — Test API

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

## Bước 7 — Build & Package

```bash
# Build Auth Service (có tests)
cd D:\Project\exchange\services\auth
.\mvnw clean package

# Build Wallet Service (có tests)
cd D:\Project\exchange\services\wallet
.\mvnw clean package

# Build không chạy tests (nhanh hơn)
.\mvnw clean package -DskipTests
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

| Lỗi                                              | Nguyên nhân                                    | Fix                                                                                                          |
| ------------------------------------------------ | ---------------------------------------------- | ------------------------------------------------------------------------------------------------------------ |
| `Connection to localhost:5432 refused`           | PostgreSQL chưa start hoặc chưa healthy        | `docker compose up -d && docker compose ps`                                                                  |
| `Could not find a valid Docker environment`      | Docker Desktop chưa mở                         | Mở Docker Desktop                                                                                            |
| `auth_db / wallet_db does not exist`             | Init script chưa chạy (volumes cũ)             | `docker compose down -v && docker compose up -d`                                                             |
| Port 5432/6379/9092 đã bị chiếm                 | App khác đang dùng port                        | Tắt app đó hoặc đổi port trong docker-compose.yml                                                           |
| `PASSWORD_TOO_WEAK` khi register                 | Password thiếu chữ hoa hoặc số                 | Dùng VD: `Secret1234`                                                                                        |
| `missing table [deposit_records]`                | Migration V1 đã apply trước khi có bảng này    | Flyway V2 sẽ tự tạo lại khi restart. Hoặc reset DB: `docker compose down -v && docker compose up -d`       |
| Wallet không tạo ví sau khi register             | Kafka chưa healthy hoặc Wallet Service chưa up | Chờ Kafka healthy rồi start lại Wallet Service                                                               |
| `401 Unauthorized` khi gọi Wallet API            | Token hết hạn hoặc sai                         | Đăng nhập lại lấy token mới                                                                                  |

---

## Cấu trúc kết nối

```
Auth Service (port 8081)
    ├── PostgreSQL :5432  →  auth_db
    ├── Redis :6379       →  rate limiting
    └── Kafka :9092       →  publish user.events.v1

Wallet Service (port 8082)
    ├── PostgreSQL :5432  →  wallet_db
    └── Kafka :9092       →  consume user.events.v1
                          →  consume trade.executed
                          →  publish wallet.transactions.v1
```
