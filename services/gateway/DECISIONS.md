# Gateway Service — Implementation Decisions

This file records implementation decisions that deviate from or are not covered by the
original spec documents. All points below have been back-ported into
`docs/SystemDesign_Appendix_APIGateway.md` and `docs/API_SPEC.md` with callout
"back-ported 2026-06" — refer to those documents for authoritative spec text.

---

## 2026-06-18 — Public-path matching: exact collection path + `exchangeInfo` (E2E fix)
**Status:** 🟡 Pending review
**Decision:** `JwtAuthenticationFilter.isPublicPath` now also matches a path equal to a
whitelist prefix without its trailing slash (so `/api/v1/trading-pairs` — not just
`/api/v1/trading-pairs/…` — is public), and `/api/v1/marketdata/exchangeInfo/` was added
to the public list.
**Reason:** Found during E2E verification: `exchangeInfo` (API_SPEC §5.4, Auth: None)
returned 401, and the canonical collection paths (`/api/v1/trading-pairs`,
`/api/v1/assets`) 401'd because `startsWith("/api/v1/trading-pairs/")` doesn't match the
slash-less form. (Note: the order service still lacks handlers for trading-pairs/assets —
see `docs/DECISIONS.md`.)
**Where:** `filter/JwtAuthenticationFilter.java`
**Suggested doc:** API_SPEC §5.4 / Gateway appendix — list `marketdata/exchangeInfo` as a
public route and note the slash-insensitive public-path match.

## 1. JWT Algorithm: HS256 instead of RS256

**Decision:** Gateway validates JWTs using HS256 with the shared `JWT_SECRET`.

**Reason:** The Auth Service (`JwtTokenProvider.java`) generates an **ephemeral RSA key
in RAM per startup** when configured for RS256. It does not persist the key pair and does
not publish a JWKS endpoint. Therefore, RS256 verification at the Gateway is impossible
in dev — the Gateway cannot obtain the corresponding public key.

**Implementation:**
- `JwtVerifier` uses `MACVerifier(secret.getBytes(UTF_8))` for HS256.
- RS256 path is preserved: if `gateway.jwt.algorithm=RS256` and `gateway.jwt.public-key`
  (PEM) is configured, it uses `RSASSAVerifier`. This enables future prod hardening once
  Auth Service persists + publishes its key.
- Default secret matches Auth default: `change-me-in-prod-must-be-at-least-32-chars!`

**Back-port target:** `docs/SystemDesign_Appendix_APIGateway.md` §4.1, §4.2 ✓

---

## 2. Kafka Wire Shapes Are NOT Uniform — Parse Per Topic

**Decision:** `WsMessageRouter.resolveRouting` uses a per-topic switch, not a single
common `EventEnvelope` deserializer.

**Reason:** The actual Kafka wire shapes differ per producer:
- `market-data.depth.v1`: raw `{pair, bids, asks, updatedAt}` — no envelope
- `market-data.kline.v1`: raw `{pair, interval, openTime, open, ...}` — no envelope
- `market-data.events.v1`: wrapped `EventEnvelope{eventId, eventType, payload:{...}}`
- `wallet.transactions.v1`: raw map (no envelope) — and topic is NOT `wallet.events.v1`
- `matching.events.v1`: wrapped `EventEnvelope` (deferred)

**Back-port target:** `docs/SystemDesign_Appendix_APIGateway.md` §7.1 ✓

---

## 3. Channel Naming: `market:<pair>:trades` (not `:ticker`)

**Decision:** External trade events route to channel `market:<pair>:trades`.

**Reason:** Frontend `TradesTape.tsx` subscribes to `market:${pair}:trades`. The spec
originally used `:ticker` in some places; verified against FE source code and corrected
to `:trades`.

**Schema string:** `market-data.events.v1.ExternalTradeObserved` (no "Event" suffix).
Reason: FE `WsStoreSyncer.tsx` listens for `market-data.events.v1.ExternalTradeObserved`
exactly. The Kafka event type is `ExternalTradeObservedEvent` but the `Event` suffix is
dropped in the schema to match FE contract.

**Back-port target:** `docs/SystemDesign_Appendix_APIGateway.md` §7.3 ✓

---

## 4. Wallet Topic: `wallet.transactions.v1` (not `wallet.events.v1`)

**Decision:** Wallet fan-out listens to `wallet.transactions.v1`.

**Reason:** Verified against `exchange-common/TopicNames.java` and wallet service code.
The `wallet.events.v1` constant exists in `TopicNames` but the wallet service publishes
to `wallet.transactions.v1` (the WalletTransaction log). The schema in the outbound
envelope uses `wallet.events.v1.WalletTransactionRecorded` to match FE `WsStoreSyncer`.

**Back-port target:** `docs/SystemDesign_Appendix_APIGateway.md` §7.1 ✓

---

## 5. Payload Transforms

The following transforms are applied before forwarding to clients:

### 5a. Kline: `openTime` (ISO-8601) → `time` (epoch seconds)
- Kafka producer emits `openTime` as ISO-8601 string (e.g. `"2026-06-01T10:00:00Z"`).
- FE `CandlestickChart.tsx` and `klineToBar` expect `time` as a Unix epoch seconds
  number (lightweight-charts format).
- Transform: `Instant.parse(openTime).getEpochSecond()`.

### 5b. Trade: `eventTime` → `executedAt`; `buyerIsMaker` → `side`
- FE `TradesTape.tsx` / `WsStoreSyncer.tsx` read `executedAt` and do not know `eventTime`.
- `side` derivation: `buyerIsMaker=true` means the buyer was the passive/maker side,
  so the aggressive/taker side was the **seller** → `side="SELL"`. And vice versa.

**Back-port target:** `docs/SystemDesign_Appendix_APIGateway.md` §7.2 ✓

---

## 6. Wallet Delta-Only Payload — Live Balance Is Incomplete

**Decision:** Forward `wallet.transactions.v1` payload as-is (only deltas, no absolutes).

**Limitation:** The payload contains `deltaAvailable`, `deltaFrozen`, `deltaTotal` but
NOT the post-transaction absolute balances. The FE wallet store (`applyBalanceChange`)
expects `available`, `frozen`, `balanceAfter` for absolute updates.

**Impact:** FE wallet balance does not auto-update correctly from the stream alone.

**Recommended fix (back-port candidates):**
- **Option A (preferred):** Wallet Service includes post-transaction absolute balances in
  `wallet.transactions.v1` payload.
- **Option B:** FE refetches `GET /wallets/me` upon receiving a `WalletTransactionRecorded`
  event.
- Gateway is stateless — it cannot derive absolutes from deltas alone.

**Back-port target:** `docs/SystemDesign_Appendix_APIGateway.md` §7.2 (wallet note) ✓

---

## 7. No Spring Security Dependency

**Decision:** Gateway does NOT include `spring-boot-starter-security`.

**Reason:** JWT validation is done manually via Nimbus in `JwtVerifier` + `JwtAuthenticationFilter`.
Adding Spring Security would require additional configuration (disable CSRF, configure
permitted paths, integrate with reactive security context) with no benefit — the custom
filter already handles authentication cleanly.

**Risk:** No CSRF protection. Acceptable because:
- Gateway is stateless (no session, no cookies for auth).
- Access tokens are Bearer tokens in Authorization headers, not cookies.
- CORS policy restricts origins.

---

## 8. Spring Cloud Version: 2025.1.1 (corrected from 2025.0.1)

**Decision:** `spring-cloud-dependencies` version `2025.1.1` (Oakwood) with artifact
`spring-cloud-starter-gateway-server-webflux`.

**Reason:** Spring Boot 4.0.6 requires the **2025.1.x** release train. The originally
chosen `2025.0.1` (Northfields) targets **Spring Boot 3.5.8** and fails *at runtime* on
Boot 4 with `NoClassDefFoundError org.springframework.boot.web.context.WebServerInitializedEvent`
(the class moved package in Boot 4). Per Spring Cloud release notes, 2025.0.x is incompatible
with Boot 4.0.1+; 2025.1.x is the train that adds Boot 4 support.

The artifact name `spring-cloud-starter-gateway-server-webflux` is unchanged across both trains.

> **Lesson:** the prior note claimed compatibility was "verified via `dependency:resolve`".
> That only proves artifacts *resolve* — not that the app *runs*. Compatibility must be
> verified by actually starting the service.

---

## 11. Custom Rate Limiter Bean Name: `haizzRedisRateLimiter`

**Decision:** The custom `com.haizz.exchange.gateway.ratelimit.RedisRateLimiter` is registered
as `@Component("haizzRedisRateLimiter")`.

**Reason:** Since the 2025.1 train, `GatewayRedisAutoConfiguration` auto-registers a bean
literally named `redisRateLimiter`. The custom component (default name `redisRateLimiter`,
derived from the class name) collided with it → `BeanDefinitionOverrideException` at startup.
The two are different *types*; `RateLimitFilter` injects by type, so an explicit, non-colliding
bean name resolves the clash without touching injection.

---

## 12. Exclude Servlet Web Stack from `exchange-common` (gateway only)

**Decision:** The gateway's dependency on `exchange-common` excludes
`spring-boot-starter-web`.

**Reason:** `exchange-common` declares `spring-boot-starter-web` (Tomcat/WebMVC) as a
compile dependency. Inherited transitively, it forced a **servlet** `ApplicationContext`,
which broke Spring Cloud Gateway (requires the **reactive** WebFlux/Netty stack) with
`No qualifying bean of type WebFluxProperties`. The exclusion is scoped to the gateway module
only — auth/wallet/marketdata legitimately use the servlet stack and are untouched.

---

## 13. Auth Route Strips `/api/v1` Prefix; Gateway Dev JWT Secret

**Decision (routing):** The `auth` route rewrites `/api/v1/auth/(?<segment>.*)` → `/auth/${segment}`
before forwarding to the auth service.

**Reason:** Auth exposes its endpoints under `/auth/**`, *not* `/api/v1/auth/**` (unlike wallet
and market-data, which already use the `/api/v1` prefix internally). Without the rewrite the
gateway forwarded `/api/v1/auth/register` to auth, which returned 401 for the unknown path.

**Decision (secret):** `application-dev.yml` sets `gateway.jwt.secret` to the same dev value
auth/wallet use (`dev-secret-key-do-not-use-in-production-32x`).

**Reason:** Auth/wallet sign & verify HS256 tokens with that dev secret (their own
`application-dev.yml`). The gateway had no dev override and fell back to the
`application.yml` default, so it rejected valid tokens with 401. Secrets must match across
all three services in dev.

> **Known minor issue:** on genuine 401s, `JwtAuthenticationFilter.writeErrorResponse` can log a
> post-commit `UnsupportedOperationException`. The client still receives the correct 401 status
> and JSON error body; left as a follow-up cleanup.

---

## 9. `/api/v1/auth/**` — No JWT at Gateway (auth handles its own auth)

**Decision:** All `/api/v1/auth/**` routes bypass `JwtAuthenticationFilter` at the Gateway.

**Reason:** Auth service handles its own authentication internally (e.g., `/auth/me` and
`/auth/logout` validate the JWT themselves via Spring Security). Gateway double-verification
would cause a circular dependency: auth calls itself to get a public key.

The spec says `/api/v1/auth/**` → NO JWT at gateway, which this implementation follows.

---

## 10. WS HandlerMapping Order: -1

**Decision:** `SimpleUrlHandlerMapping` for `/ws` has order `-1` (highest priority).

**Reason:** Spring Cloud Gateway's `RoutePredicateHandlerMapping` has a default order.
Setting the WS mapping to `-1` ensures WebSocket upgrade requests to `/ws` are handled by
`WsHandler` before the Gateway proxy dispatcher processes them.

---

*Last updated: 2026-06-14*
