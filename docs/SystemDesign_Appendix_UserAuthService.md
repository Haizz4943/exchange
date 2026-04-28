# System Design Appendix — User & Auth Service

**Parent Document:** `SystemDesign.md` v1.0
**Service:** `auth-service`
**Port:** 8081
**Bounded Context:** Identity & authentication
**Owned Entities:** `User`, `Credential`, `Session`, `LoginAttempt`
**Related SRS:** `SRS_Appendix_UserAuthService.md` v1.0
**Status:** Ready for implementation

---

## Table of Contents

1. [Scope & Design Goals](#1-scope--design-goals)
2. [Module Structure](#2-module-structure)
3. [Domain Model](#3-domain-model)
4. [Database Schema](#4-database-schema)
5. [REST API Design](#5-rest-api-design)
6. [Kafka Integration](#6-kafka-integration)
7. [Key Use Cases (Implementation)](#7-key-use-cases-implementation)
8. [JWT Issuance & Verification](#8-jwt-issuance--verification)
9. [Refresh Token Rotation & Reuse Detection](#9-refresh-token-rotation--reuse-detection)
10. [Rate Limiting (Login)](#10-rate-limiting-login)
11. [IdentityProvider Abstraction (SSO Readiness)](#11-identityprovider-abstraction-sso-readiness)
12. [Configuration](#12-configuration)
13. [Error Handling](#13-error-handling)
14. [Testing Strategy](#14-testing-strategy)
15. [Open Implementation Notes](#15-open-implementation-notes)

---

## 1. Scope & Design Goals

### 1.1 Design Goals (in priority order)

1. **Security first.** Timing-equalized login (no user enumeration), BCrypt cost 12, refresh token rotation with reuse detection, no plaintext secrets in logs or DB.
2. **SSO-ready from day one.** The `IdentityProvider` abstraction means Stage 2 SSO activation requires one new class + config change, zero refactoring of login orchestration (per SRS SR-AUTH-AP-006).
3. **Stateless access tokens.** JWT RS256, no server-side storage. Services verify locally with the public key.
4. **Minimal surface area.** Registration, login, refresh, logout, `/me`. Nothing else.
5. **Event-driven provisioning.** Registration emits `UserRegistered` via outbox; Wallet Service consumes. Auth never calls Wallet directly.

### 1.2 What's Explicitly Out of Scope

- Authorization — each downstream service owns its own access control.
- Wallet creation — Wallet Service reacts to `UserRegistered`.
- User profile (name, avatar) — post-MVP or host platform concern.
- Email verification — `email_verified` defaults to `true` in MVP.
- Password reset — post-MVP.

---

## 2. Module Structure

### 2.1 Package Layout

```
com.haizz.exchange.auth/
├── AuthServiceApplication.java
│
├── api/
│   ├── AuthController.java             # POST register, login, refresh, logout; GET /me
│   ├── InternalController.java          # POST /internal/auth/validate-token, GET /internal/auth/public-key
│   ├── SsoController.java              # POST /auth/sso/exchange (stub — 501 unless flag)
│   ├── dto/
│   │   ├── RegisterRequest.java
│   │   ├── LoginRequest.java
│   │   ├── RefreshRequest.java
│   │   ├── LogoutRequest.java
│   │   ├── TokenResponse.java
│   │   ├── UserResponse.java
│   │   ├── ValidateTokenRequest.java
│   │   └── ValidateTokenResponse.java
│   ├── mapper/
│   │   └── UserMapper.java
│   └── GlobalExceptionHandler.java
│
├── application/
│   ├── registration/
│   │   └── RegisterUseCase.java
│   ├── login/
│   │   ├── LoginUseCase.java
│   │   └── LoginAttemptRecorder.java
│   ├── refresh/
│   │   └── RefreshTokenUseCase.java
│   ├── logout/
│   │   └── LogoutUseCase.java
│   ├── query/
│   │   └── GetCurrentUserUseCase.java
│   └── sso/
│       └── SsoExchangeUseCase.java
│
├── domain/
│   ├── User.java
│   ├── Credential.java
│   ├── Session.java
│   ├── LoginAttempt.java
│   ├── UserStatus.java                  # enum: ACTIVE, DISABLED, DELETED
│   ├── IdentityProvider.java            # THE strategy interface
│   ├── UserIdentity.java               # value object
│   ├── AuthenticationRequest.java       # value object
│   └── exception/
│       ├── InvalidCredentialsException.java
│       ├── EmailAlreadyExistsException.java
│       ├── PasswordTooWeakException.java
│       ├── InvalidRefreshTokenException.java
│       ├── RefreshTokenExpiredException.java
│       ├── AccountDisabledException.java
│       └── SsoNotEnabledException.java
│
├── infrastructure/
│   ├── persistence/
│   │   ├── UserJpaEntity.java
│   │   ├── UserJpaRepository.java
│   │   ├── CredentialJpaEntity.java
│   │   ├── CredentialJpaRepository.java
│   │   ├── SessionJpaEntity.java
│   │   ├── SessionJpaRepository.java
│   │   ├── LoginAttemptJpaEntity.java
│   │   ├── LoginAttemptJpaRepository.java
│   │   └── AuthOutboxJpaEntity.java
│   ├── identity/
│   │   ├── LocalIdentityProvider.java
│   │   └── OidcIdentityProvider.java        # Stage 2 stub
│   ├── jwt/
│   │   ├── JwtIssuer.java
│   │   ├── JwtVerifier.java
│   │   └── KeyPairHolder.java
│   ├── token/
│   │   └── RefreshTokenGenerator.java
│   ├── messaging/
│   │   └── producer/
│   ├── ratelimit/
│   │   └── LoginRateLimiter.java
│   └── password/
│       ├── PasswordHasher.java
│       └── PasswordValidator.java
│
├── config/
│   ├── SecurityConfig.java
│   ├── JpaConfig.java
│   ├── KafkaConfig.java
│   ├── RedisConfig.java
│   ├── OutboxConfig.java
│   └── IdentityProviderConfig.java
│
└── shared/
    └── Constants.java
```

### 2.2 Dependency Direction

Same hexagonal rules. `IdentityProvider` is declared in `domain/`. `LocalIdentityProvider` and `OidcIdentityProvider` live in `infrastructure/identity/`. Login use case depends only on the interface. ArchUnit enforces no BCrypt imports outside `infrastructure.password` and `infrastructure.identity`.

---

## 3. Domain Model

### 3.1 `User` Aggregate

```java
public final class User {
    private final UserId id;
    private String email;
    private String emailNormalized;
    private boolean emailVerified;        // true always in MVP
    private UserStatus status;
    private String externalProvider;      // "local" | "haizz-edu"
    private String externalSubjectId;     // null for local
    private final Instant createdAt;
    private Instant updatedAt;

    public boolean isLocal() { return "local".equals(externalProvider); }
    public boolean isActive() { return status == UserStatus.ACTIVE; }

    public static User createLocal(String email) {
        return new User(UserId.generate(), email, email.trim().toLowerCase(),
            true, UserStatus.ACTIVE, "local", null, Instant.now(), Instant.now());
    }

    public static User createExternal(String email, String provider, String subjectId) {
        return new User(UserId.generate(), email, email.trim().toLowerCase(),
            true, UserStatus.ACTIVE, provider, subjectId, Instant.now(), Instant.now());
    }
}
```

### 3.2 `Session` Entity (Refresh Token)

```java
public final class Session {
    private final SessionId id;
    private final UserId userId;
    private final String refreshTokenHash;      // SHA-256 of raw token
    private final Instant issuedAt;
    private final Instant expiresAt;
    private Instant revokedAt;
    private Instant lastUsedAt;
    private String userAgent;
    private String ipAddress;

    public boolean isActive() { return revokedAt == null && expiresAt.isAfter(Instant.now()); }
    public boolean isRevoked() { return revokedAt != null; }
    public void revoke() { this.revokedAt = Instant.now(); }
}
```

### 3.3 `IdentityProvider` — The Strategy Interface

```java
public interface IdentityProvider {
    UserIdentity authenticate(AuthenticationRequest request);
    String providerName();
}

public record UserIdentity(UserId userId, String email, String provider) {}
public record AuthenticationRequest(String email, String password, String idToken) {}
```

---

## 4. Database Schema

Database: `auth_db`.

### 4.1 Tables

```sql
CREATE TABLE users (
  id                   UUID PRIMARY KEY,
  email                VARCHAR(254) NOT NULL,
  email_normalized     VARCHAR(254) NOT NULL,
  email_verified       BOOLEAN      NOT NULL DEFAULT TRUE,
  status               VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE',
  external_provider    VARCHAR(40)  NOT NULL DEFAULT 'local',
  external_subject_id  VARCHAR(128) NULL,
  created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  updated_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

  CONSTRAINT uq_users_email UNIQUE (email_normalized),
  CONSTRAINT uq_users_external UNIQUE (external_provider, external_subject_id)
);

CREATE TABLE credentials (
  user_id        UUID PRIMARY KEY REFERENCES users(id),
  password_hash  VARCHAR(255) NOT NULL,
  hash_algorithm VARCHAR(20)  NOT NULL,
  updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE sessions (
  id                  UUID PRIMARY KEY,
  user_id             UUID NOT NULL REFERENCES users(id),
  refresh_token_hash  VARCHAR(255) NOT NULL,
  issued_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  expires_at          TIMESTAMPTZ  NOT NULL,
  revoked_at          TIMESTAMPTZ  NULL,
  last_used_at        TIMESTAMPTZ  NULL,
  user_agent          VARCHAR(500) NULL,
  ip_address          VARCHAR(45)  NULL
);

CREATE INDEX ix_sessions_user_active ON sessions (user_id) WHERE revoked_at IS NULL;
CREATE INDEX ix_sessions_token_hash ON sessions (refresh_token_hash);

CREATE TABLE login_attempts (
  id            UUID PRIMARY KEY,
  email         VARCHAR(254) NULL,
  ip_address    VARCHAR(45)  NOT NULL,
  success       BOOLEAN      NOT NULL,
  attempted_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX ix_login_email ON login_attempts (email, attempted_at DESC);
CREATE INDEX ix_login_ip    ON login_attempts (ip_address, attempted_at DESC);
```

### 4.2 `auth_outbox` Table

Standard `exchange-common` outbox with dead letter table.

### 4.3 ER Diagram

```mermaid
erDiagram
    users ||--o| credentials : "user_id"
    users ||--o{ sessions : "user_id"
    users ||--o{ auth_outbox : "aggregate_id"

    users {
        uuid id PK
        varchar254 email
        varchar254 email_normalized UK
        varchar10 status
        varchar40 external_provider
        varchar128 external_subject_id
    }
    credentials {
        uuid user_id PK FK
        varchar255 password_hash
        varchar20 hash_algorithm
    }
    sessions {
        uuid id PK
        uuid user_id FK
        varchar255 refresh_token_hash
        timestamptz expires_at
        timestamptz revoked_at
    }
    login_attempts {
        uuid id PK
        varchar254 email
        boolean success
        timestamptz attempted_at
    }
```

---

## 5. REST API Design

### 5.1 Endpoint Summary

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| `POST` | `/api/v1/auth/register` | None | Register new user |
| `POST` | `/api/v1/auth/login` | None | Authenticate → tokens |
| `POST` | `/api/v1/auth/refresh` | None (token in body) | Rotate refresh → new tokens |
| `POST` | `/api/v1/auth/logout` | User JWT | Revoke session |
| `GET` | `/api/v1/auth/me` | User JWT | Current user info |
| `POST` | `/api/v1/auth/sso/exchange` | None (id_token in body) | Stage 2 SSO exchange |
| `POST` | `/internal/auth/validate-token` | Network-trust | Validate access token |
| `GET` | `/internal/auth/public-key` | Network-trust | Fetch RS256 public key |

### 5.2 Registration — No Tokens in Response

Registration returns 201 with user info but **no tokens**. User must call `/login` to authenticate. Rationale: clean flow separation — registration is a write command, login is authentication.

---

## 6. Kafka Integration

### 6.1 Produced Events (via outbox)

| Event | Topic | Partition Key | When |
|-------|-------|---------------|------|
| `UserRegistered` | `auth.events.v1` | `user_id` | After successful registration |

### 6.2 Consumed Events

None in MVP. Auth Service is a pure producer.

---

## 7. Key Use Cases (Implementation)

### 7.1 Register

```java
@Transactional
public RegisterResult execute(RegisterCommand cmd) {
    var normalizedEmail = cmd.email().trim().toLowerCase();
    if (userRepo.existsByEmailNormalized(normalizedEmail))
        throw new EmailAlreadyExistsException(normalizedEmail);
    passwordValidator.validate(cmd.password());
    var hash = passwordHasher.hash(cmd.password());
    var user = User.createLocal(cmd.email());
    userRepo.save(user);
    credentialRepo.save(new Credential(user.id(), hash, "BCRYPT"));
    outbox.write(/* UserRegistered event */);
    return RegisterResult.success(user);
}
```

### 7.2 Login

```java
public LoginResult execute(LoginCommand cmd, String ipAddress, String userAgent) {
    var normalizedEmail = cmd.email().trim().toLowerCase();
    rateLimiter.checkEmail(normalizedEmail);
    rateLimiter.checkIp(ipAddress);

    UserIdentity identity;
    try {
        identity = identityProvider.authenticate(
            new AuthenticationRequest(normalizedEmail, cmd.password(), null));
    } catch (InvalidCredentialsException e) {
        attemptRecorder.recordFailure(normalizedEmail, ipAddress);
        throw e;
    }

    var user = userRepo.findById(identity.userId()).orElseThrow();
    if (!user.isActive()) throw new AccountDisabledException();

    var accessToken = jwtIssuer.issue(user);
    var refreshToken = refreshTokenGenerator.generate();

    sessionRepo.save(new Session(SessionId.generate(), user.id(), sha256(refreshToken),
        Instant.now(), Instant.now().plus(Duration.ofDays(7)),
        null, null, userAgent, ipAddress));

    attemptRecorder.recordSuccess(normalizedEmail, ipAddress);
    return LoginResult.success(accessToken, refreshToken, 3600);
}
```

### 7.3 `LocalIdentityProvider` — Timing-Equalized BCrypt

```java
@Component
@ConditionalOnProperty(name = "auth.provider", havingValue = "local", matchIfMissing = true)
public class LocalIdentityProvider implements IdentityProvider {
    private static final String DUMMY_HASH = "$2b$12$LJ3m4qs/CzZpY0r./YPJ2OWiHP4OI3l8VRmKZV3J5B4s3V7RCmmCq";

    @Override
    public UserIdentity authenticate(AuthenticationRequest request) {
        var user = userRepo.findByEmailNormalized(request.email()).orElse(null);

        if (user == null) {
            hasher.verify("dummy-password", DUMMY_HASH);    // equalize timing
            throw new InvalidCredentialsException();
        }

        if (!user.isLocal()) {
            hasher.verify("dummy-password", DUMMY_HASH);
            throw new InvalidCredentialsException();
        }

        var credential = credentialRepo.findById(user.id()).orElseThrow(() -> {
            hasher.verify("dummy-password", DUMMY_HASH);
            return new InvalidCredentialsException();
        });

        if (!hasher.verify(request.password(), credential.passwordHash())) {
            throw new InvalidCredentialsException();
        }

        return new UserIdentity(user.id(), user.email(), "local");
    }
}
```

Every code path runs exactly one BCrypt verification — attacker cannot distinguish "user not found" from "wrong password" by timing.

### 7.4 SSO Exchange (Stage 2 Stub)

```java
public LoginResult execute(String idToken, String ip, String ua) {
    var identity = oidcProvider.authenticate(new AuthenticationRequest(null, null, idToken));
    var user = userRepo.findByExternalProviderAndSubjectId(identity.provider(), identity.externalSubjectId())
        .orElseGet(() -> {
            var newUser = User.createExternal(identity.email(), identity.provider(), identity.externalSubjectId());
            userRepo.save(newUser);
            outbox.write(/* UserRegistered */);
            return newUser;
        });
    var accessToken = jwtIssuer.issue(user);
    var refreshToken = refreshTokenGenerator.generate();
    // ... persist session
    return LoginResult.success(accessToken, refreshToken, 3600);
}
```

Returns 501 `SSO_NOT_ENABLED` unless `auth.sso.enabled=true`.

---

## 8. JWT Issuance & Verification

### 8.1 Key Pair

```bash
openssl genrsa -out jwt-private.pem 2048
openssl rsa -in jwt-private.pem -pubout -out jwt-public.pem
```

`JWT_PRIVATE_KEY` env var → Auth only. `JWT_PUBLIC_KEY` env var → Auth + Gateway.

### 8.2 Issuer

```java
@Component
public class JwtIssuer {
    public String issue(User user) {
        var claims = new JWTClaimsSet.Builder()
            .issuer("haizz-auth")
            .subject(user.id().toString())
            .claim("email", user.email())
            .claim("scope", "user")
            .jwtID(UUID.randomUUID().toString())
            .issueTime(Date.from(Instant.now()))
            .expirationTime(Date.from(Instant.now().plus(Duration.ofHours(1))))
            .build();
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        jwt.sign(new RSASSASigner(keyPair.getPrivateKey()));
        return jwt.serialize();
    }
}
```

### 8.3 Public Key Endpoint

`GET /internal/auth/public-key` → `{ "key": "<PEM>", "algorithm": "RS256" }`.

---

## 9. Refresh Token Rotation & Reuse Detection

### 9.1 Token Format

Raw: `rt_` + 32 bytes SecureRandom, Base64URL. Storage: SHA-256 hash only.

### 9.2 Refresh Flow

```java
@Transactional
public LoginResult execute(RefreshCommand cmd) {
    var hash = sha256(cmd.refreshToken());
    var session = sessionRepo.findByRefreshTokenHash(hash)
        .orElseThrow(InvalidRefreshTokenException::new);

    // REUSE DETECTION — revoked token presented again
    if (session.isRevoked()) {
        log.warn("SECURITY: Refresh token reuse for userId={}", session.userId());
        sessionRepo.revokeAllByUserId(session.userId());
        throw new InvalidRefreshTokenException("REFRESH_TOKEN_REVOKED");
    }

    if (session.expiresAt().isBefore(Instant.now()))
        throw new RefreshTokenExpiredException();

    // Rotate
    session.revoke();
    sessionRepo.save(session);

    var user = userRepo.findById(session.userId()).orElseThrow();
    if (!user.isActive()) throw new AccountDisabledException();

    var newAccessToken = jwtIssuer.issue(user);
    var newRefreshToken = refreshTokenGenerator.generate();

    sessionRepo.save(new Session(SessionId.generate(), user.id(), sha256(newRefreshToken),
        Instant.now(), Instant.now().plus(Duration.ofDays(7)),
        null, null, session.userAgent(), session.ipAddress()));

    return LoginResult.success(newAccessToken, newRefreshToken, 3600);
}
```

### 9.3 Reuse Detection — Why It Matters

If attacker steals a refresh token and uses it, the legitimate user's next refresh presents a **revoked** token → ALL sessions revoked. Limits damage: attacker gets one use, then both parties are logged out.

### 9.4 Session Cleanup

`@Scheduled(cron = "0 0 3 * * *")` — delete sessions where `expires_at < now - 30 days AND revoked_at IS NOT NULL`.

---

## 10. Rate Limiting (Login)

### 10.1 Redis Counters

```java
@Component
public class LoginRateLimiter {
    // Per-email: 5 failures in 15 min → lockout 10 min
    // Per-IP: 20 attempts in 15 min → block
    // On Redis failure: degrade to no rate limiting (log WARN)
}
```

| Condition | Response |
|-----------|----------|
| < 5 failures / 15 min | Normal 401 |
| 5th failure | Set lockout 10 min + 429 |
| During lockout | 429 regardless |
| After lockout expires | Counter expired → fresh start |
| Per-IP ≥ 20 / 15 min | 429 for that IP |
| Redis down | No rate limiting (accept risk) |

---

## 11. IdentityProvider Abstraction (SSO Readiness)

### 11.1 Provider Wiring

```java
@Configuration
public class IdentityProviderConfig {
    @Bean
    @ConditionalOnProperty(name = "auth.provider", havingValue = "local", matchIfMissing = true)
    LocalIdentityProvider localProvider(...) { ... }

    @Bean
    @ConditionalOnProperty(name = "auth.provider", havingValue = "oidc")
    OidcIdentityProvider oidcProvider(...) { ... }
}
```

Login use case injects `IdentityProvider` — doesn't know which implementation is active.

### 11.2 Stage 2 Activation Checklist

1. Implement `OidcIdentityProvider` — validate host's id_token.
2. Set `auth.provider=oidc` or dual-provider.
3. Set `auth.sso.enabled=true`.
4. Configure `auth.sso.idp.issuer`, `audience`, `jwks-uri`.
5. No changes to Gateway, Wallet, Order, Matching, Market Data, or FE core.

---

## 12. Configuration

### 12.1 `application.yml`

```yaml
spring:
  application:
    name: auth-service
  datasource:
    url: jdbc:postgresql://postgres:5432/auth_db
    username: ${DB_USER:auth_user}
    password: ${DB_PASSWORD}
    hikari:
      maximum-pool-size: 15
  jpa:
    hibernate:
      ddl-auto: validate
  flyway:
    locations: classpath:db/migration
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP:kafka:9092}
    producer:
      acks: all
      compression-type: lz4
      properties:
        enable.idempotence: true
  data:
    redis:
      host: redis
      port: 6379

server:
  port: 8081

auth:
  provider: local
  jwt:
    algorithm: RS256
    access-token-ttl: 1h
    issuer: haizz-auth
  refresh-token:
    ttl: 7d
  password:
    bcrypt-strength: 12
    min-length: 8
    require-uppercase: true
    require-lowercase: true
    require-digit: true
  rate-limit:
    max-failures-per-email: 5
    email-window: 15m
    lockout-duration: 10m
    max-attempts-per-ip: 20
    ip-window: 15m
  sso:
    enabled: false
  session:
    cleanup-cron: "0 0 3 * * *"

outbox:
  relay:
    enabled: true
    poll-interval-ms: 100
    batch-size: 100
    max-attempts: 10

logging:
  level:
    root: INFO
    com.haizz.exchange.auth: DEBUG
```

### 12.2 Spring Security Config

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/auth/register", "/api/v1/auth/login",
                    "/api/v1/auth/refresh", "/actuator/**", "/internal/**",
                    "/api/v1/auth/sso/**").permitAll()
                .anyRequest().authenticated())
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .build();
    }
}
```

---

## 13. Error Handling

### 13.1 Exception Hierarchy

```
DomainException (from exchange-common)
├── InvalidCredentialsException          → 401
├── AccountDisabledException             → 403
├── EmailAlreadyExistsException          → 409
├── PasswordTooWeakException             → 400
├── InvalidRefreshTokenException         → 401
├── RefreshTokenExpiredException         → 401
├── RateLimitExceededException           → 429
├── SsoNotEnabledException               → 501
└── InvalidIdTokenException              → 401 (Stage 2)
```

### 13.2 Security in Error Responses

- **401 for both "user not found" and "wrong password"** — always `INVALID_CREDENTIALS`.
- **429 does NOT reveal whether email exists.**
- **No stack traces** — 500s return `INTERNAL_ERROR` only.

---

## 14. Testing Strategy

### 14.1 Test Pyramid

| Layer | Count | Framework | What's Tested |
|-------|-------|-----------|---------------|
| Unit — domain | ~25 | JUnit 5 | User/Session state, email normalization, password rules |
| Unit — application | ~20 | JUnit 5 + Mockito | Use cases with mocked repos and identity provider |
| Unit — JWT | ~10 | JUnit 5 | Issue + verify roundtrip, expired, wrong issuer |
| Unit — rate limit | ~10 | JUnit 5 + embedded Redis | Counter, lockout, expiry |
| Integration — persistence | ~10 | Testcontainers PG | Flyway, unique constraints |
| Integration — full flow | ~15 | MockMvc + Testcontainers | Register → login → refresh → reuse detection |
| Architecture | ~5 | ArchUnit | Layering, no BCrypt outside infra |

### 14.2 Critical Test Scenarios

**Registration:** `validInput_creates_publishesEvent`, `duplicateEmail_409`, `weakPassword_400`.

**Login:** `validCredentials_returnsTokens`, `wrongPassword_401`, `nonexistentUser_401_sameTimingAsWrongPassword`, `disabledUser_403`, `5thFailure_lockout_429`, `afterLockoutExpires_succeeds`, `perIpLimit_429`.

**Timing equalization:**
```java
@Test
void login_timingEqualization() {
    registerUser("alice@example.com", "ValidPass1");
    var t1 = timed(() -> loginExpect401("alice@example.com", "WrongPass1"));
    var t2 = timed(() -> loginExpect401("nobody@example.com", "WrongPass1"));
    assertThat(Math.abs(t1 - t2)).isLessThan(t1 * 0.5);
}
```

**Refresh:** `validToken_rotates`, `expiredToken_401`, `revokedToken_reuseDetection_revokesAllSessions`.

**SSO:** `whenDisabled_returns501`.

**Identity Provider:** `loginUseCase_doesNotImportBCrypt_ArchUnit`, `worksWithMockProvider`.

---

## 15. Open Implementation Notes

1. **BCrypt vs Argon2id.** MVP: BCrypt cost 12. Argon2id available via `PasswordHasher` abstraction. Choose either.

2. **Refresh token delivery.** MVP: in response body, client stores in memory. Post-MVP: `httpOnly` cookie for standalone mode.

3. **JWKS endpoint.** Post-MVP: `/.well-known/jwks.json`. MVP: public key via env var.

4. **Key rotation.** MVP: static. Post-MVP: `kid` header in JWT + JWKS serves multiple keys.

5. **Email verification.** MVP: always verified. Post-MVP: `PENDING_VERIFICATION` status + email link.

6. **Password reset.** Not MVP. Post-MVP: email-based reset flow.

7. **Session limit.** MVP: unlimited. Post-MVP: cap at 10, revoke oldest on overflow.

8. **Login audit retention.** MVP: indefinite. Post-MVP: 90-day cleanup schedule.

9. **Dual-provider mode.** Separate endpoints: `/login` for local, `/sso/exchange` for SSO. Cleaner than single endpoint with content negotiation.

10. **Admin user.** Not MVP. Post-MVP: seed via migration with `scope=admin`.

---

*End of `SystemDesign_Appendix_UserAuthService.md`.*
