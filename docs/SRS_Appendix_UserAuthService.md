# SRS Appendix — User & Auth Service

**Parent Document:** `SRS.md` v1.0
**Service:** User & Auth Service
**Status:** Design complete, implementation not started
**Owned Entities:** `User`, `Credential`, `Session` (refresh token tracking)

---

## 1. Purpose & Boundaries

The User & Auth Service is the identity provider for the platform. In Stage 1 (standalone), it authenticates users directly. In Stage 2 (embedded in the education platform), it accepts delegated identity from the host via SSO and provisions local user records lazily. In both cases, it is the sole issuer of JWTs that downstream services trust.

This service must be designed with SSO readiness from the start — BR-016 is a Must requirement, even though the SSO integration itself is Stage 2. Failing to account for SSO early would require retrofitting authentication semantics later.

### 1.1 Service Responsibilities

- Register new users (email + password in Stage 1; external IdP claims in Stage 2).
- Authenticate users (login with email/password → JWT).
- Issue JWT access tokens (short TTL) and refresh tokens (longer TTL).
- Validate and revoke refresh tokens.
- Provision downstream state for new users by emitting `UserRegistered` on Kafka (Wallet Service reacts to create wallets and credit initial USDT).
- Rate-limit authentication attempts to prevent bruteforce.
- Expose JWT public keys (if RS256) or validate tokens internally for gateway use.
- Abstract identity source so Stage 2 SSO can be plugged in without downstream changes.

### 1.2 Service Non-Responsibilities

- Wallet creation (Wallet Service reacts to `UserRegistered`).
- Authorization logic beyond authentication (each service enforces its own access control using `user_id` from the JWT).
- User profile fields beyond identity (name, bio, avatar — if any — are Stage 2+ concerns).

### 1.3 Bounded Context

"Identity and authentication." User profile, preferences, and education-platform-linked data live outside this context (in the host education platform in Stage 2).

---

## 2. Domain Model

### 2.1 Aggregate — User

```
User (aggregate root)
├── id: UUID (PK)
├── email: String (unique, normalized lowercase)
├── email_verified: Boolean (MVP: always true; email verification is post-MVP)
├── status: Enum [ACTIVE, DISABLED, DELETED]
├── external_provider: String NULL        -- "local" | "haizz-edu" | future IdPs
├── external_subject_id: String NULL      -- subject ID from external IdP
├── created_at: Timestamp
└── updated_at: Timestamp
```

**Invariants:**
- Email is unique across all active users (case-insensitive).
- If `external_provider != "local"`, `external_subject_id` is required.
- `(external_provider, external_subject_id)` is unique when both are set (one local user per external identity).

### 2.2 Entity — Credential (only for local users)

```
Credential
├── user_id: UUID (PK, FK to User)
├── password_hash: String (bcrypt or argon2id)
├── hash_algorithm: Enum [BCRYPT, ARGON2ID]
├── updated_at: Timestamp
```

Users with `external_provider != "local"` do not have a Credential row.

### 2.3 Entity — Session (Refresh Token)

```
Session
├── id: UUID (PK)                    -- refresh token ID
├── user_id: UUID (FK)
├── refresh_token_hash: String        -- hash of the token; raw token never stored
├── issued_at: Timestamp
├── expires_at: Timestamp
├── revoked_at: Timestamp NULL
├── last_used_at: Timestamp NULL
├── user_agent: String NULL
├── ip_address: String NULL
```

On refresh, the old Session is marked `revoked_at`, a new Session is issued (rotation).

### 2.4 Entity — LoginAttempt (for rate limiting audit)

```
LoginAttempt
├── id: UUID (PK)
├── email: String
├── ip_address: String
├── success: Boolean
├── attempted_at: Timestamp
```

Used for observability and auditing rate limits. Live rate-limit state is in Redis.

---

## 3. API Specifications

### 3.1 Public REST Endpoints

#### 3.1.1 Register

```
POST /auth/register
Content-Type: application/json

Body:
{
  "email": "alice@example.com",
  "password": "Secret1234"
}

Responses:
  201 Created
  {
    "user_id": "7d3e...",
    "email": "alice@example.com",
    "created_at": "..."
  }

  400 Bad Request — { code: "INVALID_EMAIL" | "PASSWORD_TOO_WEAK" }
  409 Conflict — { code: "EMAIL_ALREADY_EXISTS" }
  429 Too Many Requests — { code: "RATE_LIMIT_EXCEEDED" }
```

**Behaviour:**
- Normalize email (lowercase, trim).
- Validate password per SR-AUTH-003.
- Hash password with bcrypt cost 12 (or argon2id).
- Insert User with `status=ACTIVE, external_provider="local"`.
- Insert Credential.
- Publish `UserRegistered{user_id, email, created_at, external_provider: "local"}` on Kafka.
- Return 201 (note: response does NOT include tokens; user must log in to receive tokens).

**Acceptance Criteria:**

- **Given** no user `alice@example.com` exists, **when** valid registration is submitted, **then** response is 201, User and Credential are persisted, `UserRegistered` event is published, and by eventual consistency Wallet Service creates 6 wallets with USDT=10,000.
- **Given** a user `alice@example.com` already exists (case-insensitive match), **when** `Alice@Example.com` is submitted, **then** response is 409 `EMAIL_ALREADY_EXISTS`.
- **Given** password `abc` (fails complexity), **when** registration is submitted, **then** response is 400 `PASSWORD_TOO_WEAK`.

#### 3.1.2 Login

```
POST /auth/login
Content-Type: application/json

Body:
{
  "email": "alice@example.com",
  "password": "Secret1234"
}

Responses:
  200 OK
  {
    "access_token": "eyJhbGciOi...",
    "refresh_token": "rt_abc123...",
    "token_type": "Bearer",
    "expires_in": 3600
  }

  401 Unauthorized — { code: "INVALID_CREDENTIALS" }   // generic for wrong email OR password
  403 Forbidden — { code: "ACCOUNT_DISABLED" }
  429 Too Many Requests — { code: "RATE_LIMIT_EXCEEDED" }
```

**Behaviour:**
- Look up user by normalized email.
- If user does not exist OR user is not local (SSO-only): respond 401 `INVALID_CREDENTIALS` (same response to avoid user enumeration).
- If user exists + password fails verification: log a failed LoginAttempt, increment Redis counter, respond 401.
- If rate limit exceeded (5 fails in 15min): respond 429, lockout 10 min from Redis.
- On success: issue access token (JWT, 1h TTL) + refresh token (opaque string, 7d TTL); persist Session.

**Acceptance Criteria:**

- **Given** valid credentials, **when** login is submitted, **then** response is 200 with tokens; Session is persisted; LoginAttempt with `success=true` recorded.
- **Given** the email doesn't exist, **when** login is submitted, **then** response is 401 `INVALID_CREDENTIALS` (NOT a specific "no such user" code).
- **Given** the password is wrong, **when** login is submitted, **then** response is 401 `INVALID_CREDENTIALS`, fail counter in Redis increments.
- **Given** 5 failed attempts within 15 minutes for the same email, **when** a 6th attempt is made, **then** response is 429 for 10 minutes, regardless of whether credentials would be correct.

#### 3.1.3 Refresh

```
POST /auth/refresh
Content-Type: application/json

Body:
{
  "refresh_token": "rt_abc123..."
}

Responses:
  200 OK — same shape as /login, with new tokens (rotation)
  401 Unauthorized — { code: "INVALID_REFRESH_TOKEN" | "REFRESH_TOKEN_EXPIRED" | "REFRESH_TOKEN_REVOKED" }
```

**Behaviour:**
- Hash incoming refresh token; look up Session by hash.
- Validate: not revoked, not expired.
- Revoke old Session (set `revoked_at`).
- Issue new access + refresh token pair; persist new Session.
- Return new tokens.

**Security note — refresh token reuse detection:** If a revoked refresh token is presented again, the service treats it as a compromise indicator: revoke all active Sessions for that user, log a SECURITY event, return 401 with generic code. Post-MVP: notify user via email.

**Acceptance Criteria:**

- **Given** a valid non-expired refresh token, **when** refresh is called, **then** response is 200 with new tokens; old Session is marked revoked.
- **Given** a revoked refresh token is presented, **when** refresh is called, **then** response is 401 AND all Sessions for the user are revoked.
- **Given** an expired refresh token, **when** refresh is called, **then** response is 401 `REFRESH_TOKEN_EXPIRED`.

#### 3.1.4 Logout

```
POST /auth/logout
Authorization: Bearer <access_token>

Body:
{
  "refresh_token": "rt_abc123..."   // optional; if provided, revokes that session
}

Responses:
  204 No Content
```

**Behaviour:**
- If refresh_token provided: mark that Session revoked.
- Access token itself is not invalidated (it's stateless JWT); it simply expires naturally within 1h.
- For MVP, logout without refresh_token is a no-op at the server; FE discards tokens locally.

Post-MVP: token blacklist in Redis for immediate access-token revocation if needed.

#### 3.1.5 Get Current User

```
GET /auth/me
Authorization: Bearer <access_token>

Responses:
  200 OK
  {
    "user_id": "7d3e...",
    "email": "alice@example.com",
    "external_provider": "local",
    "status": "ACTIVE"
  }

  401 Unauthorized
```

### 3.2 Internal Endpoints

```
POST /internal/auth/validate-token
Body: { "token": "..." }
Responses:
  200 OK
  { "valid": true, "user_id": "...", "expires_at": "..." }
  { "valid": false, "reason": "EXPIRED" | "INVALID_SIGNATURE" | "USER_DISABLED" }
```

Used by API Gateway for token validation on each request. If RS256 is used and the public key is distributed to the Gateway, local validation is preferred for performance.

### 3.3 SSO Endpoints (Stage 2 — scaffolded in MVP, not active)

```
POST /auth/sso/exchange
Body: { "id_token": "<JWT from host IdP>" }
Responses:
  200 OK — same shape as /login
  401 Unauthorized — { code: "INVALID_ID_TOKEN" }
```

**Behaviour (Stage 2):**
- Validate the external ID token against the trusted IdP (host education platform) — signature, issuer, audience, not-expired.
- Extract `sub` and `email` claims.
- Look up local User by `(external_provider="haizz-edu", external_subject_id=sub)`.
- If not found: provision User (no Credential), publish `UserRegistered`.
- Issue local JWT pair as in standard login.

**MVP:** Endpoint exists and returns 501 `SSO_NOT_ENABLED` unless a feature flag `auth.sso.enabled=true` is set. Implementation is stubbed to enable fast Stage-2 activation.

### 3.4 JWT Structure

**Access Token Claims:**

```json
{
  "iss": "haizz-auth",
  "sub": "<user_id UUID>",
  "email": "alice@example.com",
  "iat": 1713600000,
  "exp": 1713603600,
  "jti": "<uuid>",
  "scope": "user"
}
```

**Signing algorithm:** RS256 for MVP (key pair on filesystem/secret manager). HS256 fallback allowed for solo-dev local environment (shared secret via env var).

**Key rotation:** Not implemented in MVP. Post-MVP: key rotation with `kid` header and a JWKS endpoint.

---

## 4. Functional Requirements (Detailed)

Inherits and expands SRS §3.1.

### SR-AUTH-AP-001 — Email Normalization

**Requirement:** All email comparisons are case-insensitive. Store the lowercase form; retain the original case only if needed for display (MVP: not needed).

**Acceptance Criteria:**
- Given `Alice@Example.com` is registered, attempting to register `alice@example.com` returns 409.

### SR-AUTH-AP-002 — Password Hashing

**Requirement:** bcrypt cost ≥ 12 OR argon2id (time cost 2, memory 64 MB, parallelism 1).

**Acceptance Criteria:**
- Given a user registers, the stored `password_hash` starts with `$2b$12$` (bcrypt cost 12) or `$argon2id$...`.
- No plaintext password ever written to DB, logs, or metrics.

### SR-AUTH-AP-003 — Initial Wallet Provisioning via Event

**Requirement:** This service does NOT call Wallet Service directly. It publishes `UserRegistered` and Wallet Service reacts.

**Rationale:** Decoupling; Wallet Service owns wallet creation logic; event-driven alignment with BRD NFR-010.

**Acceptance Criteria:**
- Given a registration succeeds, a single `UserRegistered` event with `event_id` is written to the outbox and eventually published. Wallet Service, on consumption, creates 6 wallets and credits USDT=10,000.
- Given the Kafka publish fails, the outbox relay retries; the user can log in but wallet queries may temporarily return "no wallets yet" — FE displays a loading state.
- Post-MVP: consider a stronger bootstrap (synchronous wallet creation on first login if wallet is missing, for better UX).

### SR-AUTH-AP-004 — Rate Limiting

**Requirement:** Per-email login attempt rate limit.

**Implementation:** Redis key `auth:login_fails:{normalized_email}` with INCR on failure and EXPIRE 900s (15min). On value ≥ 5, set `auth:lockout:{email}` with EXPIRE 600s (10min). Check both keys on login.

**Acceptance Criteria:**
- Given 5 failed logins within 15 min, the 6th login attempt returns 429.
- Given 10 min pass after lockout, login attempts resume.
- Given a failed login spaced > 15 min from previous failures, the counter has expired and does not trigger lockout.

**Per-IP supplemental limit:** `auth:login_attempts_ip:{ip}` with 20 attempts per 15 minutes, all-or-nothing. Prevents distributed bruteforce via same IP targeting many emails.

### SR-AUTH-AP-005 — Token Revocation on Account Disable

**Requirement:** If User.status is changed to DISABLED (admin action, post-MVP via CLI only), all active Sessions are immediately revoked. Access tokens in the wild expire within 1h (acceptable for MVP).

**Acceptance Criteria:**
- Given a user is disabled via admin action, `SELECT * FROM sessions WHERE user_id = ? AND revoked_at IS NULL` returns 0 rows after the action.
- The user's access token continues to be accepted by services until its natural expiry (≤ 1h). The `POST /internal/auth/validate-token` endpoint checks User.status and returns `USER_DISABLED` for disabled users, short-circuiting this window.

### SR-AUTH-AP-006 — SSO Readiness (Scaffolding)

**Requirement:** The service must be structured such that activating SSO is a configuration change, not a refactor.

**Design:**
- `IdentityProvider` interface:
  ```
  authenticate(credentials) → UserIdentity
  // where credentials may be {email, password} for local or {id_token} for SSO
  ```
- MVP implementation: `LocalIdentityProvider`. Stage-2 implementation: `OidcIdentityProvider` configured via Spring `@ConditionalOnProperty`.
- Login endpoint uses injected `IdentityProvider`. SSO endpoint would use a different provider OR the same endpoint with content negotiation on body shape. Currently, separate endpoint keeps things clear.

**Acceptance Criteria:**
- Inspecting the codebase, the login orchestration does not mention "password" or "bcrypt" directly — it delegates to `IdentityProvider.authenticate`.
- Adding a stub `OidcIdentityProvider` and setting `auth.sso.enabled=true, auth.sso.idp.url=...` should enable SSO end-to-end in Stage 2 without touching login orchestration code.

---

## 5. Data Model & Persistence

### 5.1 Tables

```sql
CREATE TABLE users (
  id                   UUID PRIMARY KEY,
  email                VARCHAR(254) NOT NULL,
  email_normalized     VARCHAR(254) NOT NULL UNIQUE,
  email_verified       BOOLEAN NOT NULL DEFAULT TRUE,
  status               VARCHAR(10) NOT NULL DEFAULT 'ACTIVE',
  external_provider    VARCHAR(40) NOT NULL DEFAULT 'local',
  external_subject_id  VARCHAR(128) NULL,
  created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (external_provider, external_subject_id)
);

CREATE TABLE credentials (
  user_id         UUID PRIMARY KEY REFERENCES users(id),
  password_hash   VARCHAR(255) NOT NULL,
  hash_algorithm  VARCHAR(20)  NOT NULL,
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE sessions (
  id                   UUID PRIMARY KEY,
  user_id              UUID NOT NULL REFERENCES users(id),
  refresh_token_hash   VARCHAR(255) NOT NULL,
  issued_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  expires_at           TIMESTAMPTZ NOT NULL,
  revoked_at           TIMESTAMPTZ NULL,
  last_used_at         TIMESTAMPTZ NULL,
  user_agent           VARCHAR(500) NULL,
  ip_address           VARCHAR(45)  NULL
);

CREATE INDEX ix_sessions_user_active ON sessions (user_id) WHERE revoked_at IS NULL;
CREATE INDEX ix_sessions_token_hash ON sessions (refresh_token_hash);

CREATE TABLE login_attempts (
  id             UUID PRIMARY KEY,
  email          VARCHAR(254) NULL,   -- may be null for malformed requests
  ip_address     VARCHAR(45)  NOT NULL,
  success        BOOLEAN      NOT NULL,
  attempted_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX ix_login_attempts_email ON login_attempts (email, attempted_at DESC);
CREATE INDEX ix_login_attempts_ip ON login_attempts (ip_address, attempted_at DESC);

CREATE TABLE auth_outbox (
  id            UUID PRIMARY KEY,
  event_type    VARCHAR(40) NOT NULL,
  aggregate_id  UUID NOT NULL,
  payload_json  JSONB NOT NULL,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  published_at  TIMESTAMPTZ NULL,
  attempts      INT NOT NULL DEFAULT 0
);
```

---

## 6. Security Considerations

| Area | Control |
|------|---------|
| Password storage | bcrypt cost 12 or argon2id; never plaintext. |
| Token storage (client) | FE stores access token in memory (not localStorage); refresh token in httpOnly cookie ideally, or localStorage as MVP fallback. Decision deferred to FE design phase. |
| Token storage (server) | Refresh tokens: only hash stored. Access tokens: not stored (stateless JWT). |
| Transport | HTTPS mandatory for all auth endpoints. |
| Cross-site | SameSite=Strict on any auth cookie; CORS configured to allowlist only the FE origin(s). |
| Timing attacks | Password verification runs even for non-existent users (use a dummy hash) to avoid timing leak. |
| Email enumeration | Uniform `401 INVALID_CREDENTIALS` for both "no such user" and "wrong password". |
| Bruteforce | Per-email and per-IP rate limits (§ SR-AUTH-AP-004). |
| Refresh token theft | Rotation on every refresh + reuse detection (§3.1.3 security note). |
| JWT secret / key management | Env var for HS256 secret; file path or env var for RS256 private key. Never committed to source control. |
| Audit | LoginAttempt rows retained indefinitely for MVP. |

---

## 7. Edge Cases & Error Handling

| ID | Scenario | Required Behavior |
|----|----------|-------------------|
| SR-AUTH-EDGE-001 | User registers while Kafka is down. | Outbox buffers the event; user is registered; Wallet Service provisions wallets when Kafka recovers. Login works immediately; wallets endpoint may be empty transiently. |
| SR-AUTH-EDGE-002 | User attempts to log in with correct email but case-variant. | Normalization ensures match; login succeeds. |
| SR-AUTH-EDGE-003 | Two concurrent registrations with same email. | Unique constraint on `email_normalized` ensures one succeeds, the other gets 409 via caught constraint violation. |
| SR-AUTH-EDGE-004 | User refreshes with a not-yet-expired but revoked refresh token. | Reuse-detection: all user sessions revoked; 401 returned. |
| SR-AUTH-EDGE-005 | User registers with an email > 254 chars. | 400 `INVALID_EMAIL`. |
| SR-AUTH-EDGE-006 | Rate limit counter in Redis is lost (Redis restart). | Counter resets to 0; lockout is forgotten. Acceptable for MVP (lockout is soft defense). Post-MVP: persist counters or use DB-backed rate limiting. |
| SR-AUTH-EDGE-007 | JWT signing key is rotated (Stage 2+). | New tokens use new key. Old tokens remain valid until expiry. JWKS endpoint (post-MVP) serves both keys. For MVP, key rotation requires restarting all services with new key — acceptable. |
| SR-AUTH-EDGE-008 | SSO endpoint is called in MVP before Stage 2 feature flag enabled. | Return 501 `SSO_NOT_ENABLED`. |
| SR-AUTH-EDGE-009 | User deletes account (Stage 2+, not MVP). | User.status → DELETED; sessions revoked; wallets retained (for audit); email marked unusable (post-MVP: allow re-registration with same email after 30 days, or permanently block). |
| SR-AUTH-EDGE-010 | `/auth/me` called with expired access token. | 401 `TOKEN_EXPIRED`; FE should refresh. |

---

## 8. Traceability

| Auth Service Requirement | Parent SRS Requirement(s) | BRD Requirement(s) |
|-------------------------|--------------------------|--------------------|
| SR-AUTH-AP-001 | SR-AUTH-002 | BR-001 |
| SR-AUTH-AP-002 | SR-AUTH-004 | NFR-008 |
| SR-AUTH-AP-003 | SR-AUTH-007, SR-AUTH-008 | BR-002 |
| SR-AUTH-AP-004 | US-AUTH-002 acceptance | NFR-008 |
| SR-AUTH-AP-005 | SR-EDGE-011 | NFR-008 |
| SR-AUTH-AP-006 | SR-AUTH-009 | BR-016 |

---

## 9. Implementation Notes for Coding Agent

- **Spring Security:** Use Spring Security 6 with JWT resource-server support. Custom `IdentityProvider` interface layered over `AuthenticationManager`.
- **BCrypt:** `BCryptPasswordEncoder` with strength 12. For argon2id, use `Argon2PasswordEncoder` (Spring Security built-in).
- **JWT library:** `io.jsonwebtoken:jjwt` or `com.nimbusds:nimbus-jose-jwt`. Either is fine; nimbus is more modern.
- **Refresh token storage:** Generate a 32-byte random token (Base64URL-encoded), hash with SHA-256 before DB storage. Comparison is constant-time.
- **Redis rate-limit:** Redis INCR + EXPIRE pattern. Lua script for atomic check-and-set if needed.
- **Outbox:** Same pattern as Order Service. Consider a shared outbox utility in `exchange-common` post-MVP.
- **Testing:** Pay special attention to the timing-attack test (assert login duration is constant whether user exists or not). Use Testcontainers for Postgres + Redis integration tests.
- **SSO stub:** Even though MVP is local-only, write the `IdentityProvider` abstraction and one `LocalIdentityProvider` implementation. This satisfies BR-016 with minimal overhead and pays off immediately in Stage 2.

---

*End of `SRS_Appendix_UserAuthService.md`.*
