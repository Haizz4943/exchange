# Decisions — Repo-level (cross-cutting)

Implementation decisions not (yet) covered by the specs. The user reviews these
and decides whether to back-port into SRS / System Design / API_SPEC.

**Status legend:** 🟡 Pending review · ✅ Back-ported to docs · 📌 Keep code-only · ❌ Reverted

## 2026-06-18 — `@EnableKafka` required on each consumer service (Spring Boot 4)
**Status:** 🟡 Pending review
**Decision:** Added `@EnableKafka` to the `KafkaConfig` of wallet, order, and matching.
**Why:** Under Spring Boot 4.0.6, defining a custom `kafkaListenerContainerFactory` bean suppresses Boot's auto-registration of the `@KafkaListener` annotation processor, so **no `@KafkaListener` consumer started** in any service — registration never provisioned wallets, orders never matched, no trades. Found during E2E verification (zero Kafka consumer groups despite healthy services). The specs assume the consumers run but don't state this Boot-4 requirement.
**Where:** `services/{wallet,order,matching}/.../config/KafkaConfig.java`
**Suggested doc:** SystemDesign (Kafka/messaging section) + per-service appendices — note that every consumer service must declare `@EnableKafka` when it supplies its own listener container factory on Boot 4.

## 2026-06-18 — User-cancel finalized via matching ACK (`USER_CANCELLED`), state-only
**Status:** 🟡 Pending review
**Decision:** On a user `DELETE /orders/{id}`, the order goes `CANCEL_REQUESTED`; the Matching Engine, after removing the resting order from its index, emits an `OrderCancelled` confirmation on `matching.events.v1` with reason `USER_CANCELLED`. The Order service consumes it and finalizes `CANCEL_REQUESTED → CANCELLED` **without** releasing the freeze again (the DELETE path already released it). Matching-driven cancels (`REJECTED`/`MARKET_PARTIAL`, prior state ≠ CANCEL_REQUESTED) still release the residual as before. The release-vs-skip decision is made on the order's **prior state**, not the reason string, so it is robust to replay.
**Why:** API_SPEC §3.2 says "final CANCELLED arrives via WebSocket when Matching Engine confirms", but matching emitted no such confirmation — user-cancelled orders were stranded in `CANCEL_REQUESTED` forever. Naively reusing the existing matching-driven cancel handler would **double-release** the freeze (DELETE releases under reason `CANCELLED`, the handler under `FILL_RESIDUAL`). The state-based skip avoids that. The `CANCEL_REQUESTED` intermediate is retained so an in-flight fill can still win the race (`CANCEL_REQUESTED → FILLED`).
**Where:** `services/matching/.../application/{OrderDispatcher,FillEmitter}.java`, `services/matching/.../kafka/OrderEventsConsumer.java`; `services/order/.../application/{FillPersister,ProcessFillEventUseCase}.java`
**Suggested doc:** API_SPEC §3.2 + §8.2 (`matching.events.v1`) — document the `USER_CANCELLED` confirmation event and that the Order service finalizes state without a second freeze release.

## 2026-06-18 — `/api/v1/trading-pairs` & `/api/v1/assets` not implemented (MVP)
**Status:** 🟡 Pending review
**Decision:** These two endpoints are documented in API_SPEC §3.5/§3.6 (Auth: None) but have **no controller** in the Order service; the gateway routes them but the order service returns no handler. The frontend sources pair metadata from the (public) `GET /api/v1/marketdata/exchangeInfo` instead, which carries `tickSize`/`stepSize`/`minNotional`. Left unimplemented for MVP rather than adding net-new endpoints during a verification pass.
**Why:** Discovered during E2E verification (404/`NoResourceFoundException`). Functionally redundant with `exchangeInfo` for the current frontend; implementing them is scope beyond verification.
**Where:** `services/order` (no `TradingPairController` / `AssetController`)
**Suggested doc:** API_SPEC §3.5/§3.6 — either mark these as deferred/post-MVP, or implement them; until then the authoritative public pair source is `marketdata/exchangeInfo`.
