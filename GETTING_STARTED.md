# Getting Started — Haizz Exchange (Auth Service)

## Yêu cầu

| Tool           | Phiên bản |
| -------------- | --------- |
| JDK            | 21        |
| Maven          | 3.9.15    |
| Docker Desktop | 29.2.1    |
| IDE            | VS Code   |
| DB Client      | DBeaver   |
| API Client     | Postman   |

> Maven không cần cài riêng — dùng `mvnw` wrapper có sẵn trong project.

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

## Bước 3 — Chạy Auth Service

### Cách 1: Dùng Maven Wrapper (terminal)

```ps
cd D:\Project\exchange\auth
$env:SPRING_PROFILES_ACTIVE="dev"
$env:JWT_SECRET="dev-secret-key-do-not-use-in-production-32x"
.\mvnw spring-boot:run
```

### Cách 2: Dùng VS Code

1. Mở VS Code → **File → Open Folder** → chọn `D:\Project\exchange`
2. Cài extensions nếu chưa có:
   - **Extension Pack for Java** (Microsoft)
   - **Spring Boot Extension Pack** (VMware)
3. Tạo file `.vscode/launch.json` (nếu chưa có) với nội dung:

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
        "SPRING_PROFILES_ACTIVE": "dev",
        "JWT_SECRET": "dev-secret-key-do-not-use-in-production-32x"
      }
    }
  ]
}
```

4. Nhấn **F5** hoặc vào tab **Run and Debug** (Ctrl+Shift+D) → chọn **Auth Service** → **Start Debugging**

### Service đã start thành công khi thấy log:

```
Started AuthApplication in X.XXX seconds
Tomcat started on port 8081
```

---

## Bước 4 — Test API

### Đăng ký tài khoản

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

### Đăng nhập

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

### Lấy thông tin user (cần Bearer token)

```bash
curl http://localhost:8081/auth/me \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiI4ZjJmNjZmOS04N2M5LTQ5NGMtODA2OS1iNmFhMjRhMDQwNTAiLCJzY29wZSI6InVzZXIiLCJpc3MiOiJoYWl6ei1hdXRoIiwiZXhwIjoxNzc3MjM3NDI1LCJpYXQiOjE3NzcyMzM4MjUsImVtYWlsIjoiYWxpY2VAZXhhbXBsZS5jb20iLCJqdGkiOiIxYTRiN2U2Yy1iNzgzLTQ0OWItODhiMS03YmVmMmQ5MDlmOGMifQ.C6amT5Eo4lQTKqm9_8pMrh09H8Wapgj5xYLSDUhybJ0"
```

### Refresh token

```bash
curl -X POST http://localhost:8081/auth/refresh \
  -H "Content-Type: application/json" \
  -d "{\"refresh_token\": \"<refresh_token>\"}"
```

### Logout

```bash
curl -X POST http://localhost:8081/auth/logout \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiI4ZjJmNjZmOS04N2M5LTQ5NGMtODA2OS1iNmFhMjRhMDQwNTAiLCJzY29wZSI6InVzZXIiLCJpc3MiOiJoYWl6ei1hdXRoIiwiZXhwIjoxNzc3MjM3NDI1LCJpYXQiOjE3NzcyMzM4MjUsImVtYWlsIjoiYWxpY2VAZXhhbXBsZS5jb20iLCJqdGkiOiIxYTRiN2U2Yy1iNzgzLTQ0OWItODhiMS03YmVmMmQ5MDlmOGMifQ.C6amT5Eo4lQTKqm9_8pMrh09H8Wapgj5xYLSDUhybJ0" \
  -H "Content-Type: application/json" \
  -d "{\"refresh_token\": \"<refresh_token>\"}"
```

### Validate token (internal endpoint — dành cho Gateway)

```bash
curl -X POST http://localhost:8081/internal/auth/validate-token \
  -H "Content-Type: application/json" \
  -d "{\"token\": \"eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiI4ZjJmNjZmOS04N2M5LTQ5NGMtODA2OS1iNmFhMjRhMDQwNTAiLCJzY29wZSI6InVzZXIiLCJpc3MiOiJoYWl6ei1hdXRoIiwiZXhwIjoxNzc3MjM3NDI1LCJpYXQiOjE3NzcyMzM4MjUsImVtYWlsIjoiYWxpY2VAZXhhbXBsZS5jb20iLCJqdGkiOiIxYTRiN2U2Yy1iNzgzLTQ0OWItODhiMS03YmVmMmQ5MDlmOGMifQ.C6amT5Eo4lQTKqm9_8pMrh09H8Wapgj5xYLSDUhybJ0\"}"
```

---

## Bước 5 — Build & Package

```bash
# Build có chạy tests (cần Docker đang chạy)
cd D:\Project\exchange
.\auth\mvnw clean install -pl auth -am

# Build không chạy tests (nhanh hơn)
.\auth\mvnw clean install -pl auth -am -DskipTests
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

| Lỗi                                         | Nguyên nhân                             | Fix                                               |
| ------------------------------------------- | --------------------------------------- | ------------------------------------------------- |
| `Connection to localhost:5432 refused`      | PostgreSQL chưa start hoặc chưa healthy | `docker compose up -d && docker compose ps`       |
| `Could not find a valid Docker environment` | Docker Desktop chưa mở                  | Mở Docker Desktop                                 |
| `auth_db does not exist`                    | Init script chưa chạy (volumes cũ)      | `docker compose down -v && docker compose up -d`  |
| Port 5432/6379/9092 đã bị chiếm             | App khác đang dùng port                 | Tắt app đó hoặc đổi port trong docker-compose.yml |
| `PASSWORD_TOO_WEAK` khi register            | Password thiếu chữ hoa hoặc số          | Dùng VD: `Secret1234`                             |

---

## Cấu trúc kết nối

```
Auth Service (port 8081)
    ├── PostgreSQL :5432   →  database auth_db
    ├── Redis :6379        →  rate limiting
    └── Kafka :9092        →  publish UserRegistered event
```
