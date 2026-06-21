# Business Requirements Document (BRD)

**Project Name:** Simulated Crypto Trading Platform (Paper Trading) — internal codename *Haizz Exchange*
**Version:** 1.0
**Date:** April 19, 2026
**Author:** Haizz (Product Owner & Developer)
**Status:** Draft — pending stakeholder review

---

## 1. Executive Summary

This project delivers a **simulated cryptocurrency trading platform** designed to give learners a risk-free, production-like environment to practice spot trading. The platform mirrors the user experience of a real exchange (Binance-style) — order book, real-time charting, market/limit orders, wallet management — but all funds are virtual and all deposits/withdrawals are simulated. Real-time market prices are sourced from live external exchanges (Binance for crypto; SSI/TCBS for Vietnamese equities in later phases) so that learners experience authentic market dynamics without real financial exposure.

The platform is built first as a **standalone proof-of-concept (POC)** and subsequently **embedded as a feature module** into an existing online education application. The underlying backend is designed as a microservices architecture from day one — not because the MVP needs that scale, but because the architecture itself is a learning/portfolio objective and must support future expansion (Vietnamese stocks, margin, futures) without rewrites.

Success means learners can place orders on major crypto pairs (BTC/USDT, ETH/USDT, etc.) against live market prices, see their simulated portfolio evolve realistically, and instructors can use the tool as part of their curriculum — all delivered within a three-month MVP window by a solo developer.

---

## 2. Business Objectives

| # | Objective | Success Metric | Target |
|---|-----------|---------------|--------|
| 1 | Deliver a working standalone POC demonstrating end-to-end spot trading | POC deployable and usable by test users | MVP live within 3 months |
| 2 | Enable seamless embedding into the existing education platform | Frontend module can be mounted inside host Next.js app with shared authentication | Integration feasible with ≤ 2 weeks of host-app work |
| 3 | Support realistic market conditions using live external price feeds | Price lag between external source and platform display | ≤ 2 seconds (95th percentile) |
| 4 | Support the target scale when embedded into the education platform | Concurrent active users supported without degradation | 100–1,000 concurrent users |
| 5 | Establish an architecture that supports future expansion | Ability to add new asset classes (VN stocks) without rewriting core services | New market addable via Market Data Service only |

---

## 3. Stakeholders

| Stakeholder | Role | Responsibility | Interest Level |
|-------------|------|---------------|----------------|
| Haizz | Product Owner & Lead Developer | Define scope, design architecture, implement all services, make tech decisions | High |
| Education Platform Owner | Integration partner / internal customer | Host application team — defines integration requirements, SSO contract, UX constraints | High |
| Learners (end users) | Primary users | Practice trading, manage virtual portfolio | High (as user) |
| Instructors | Secondary users | Use platform as a teaching aid; may review learner activity in future phases | Medium |
| External data providers | Upstream dependency | Binance (crypto), SSI/TCBS (VN equities) — provide price feeds; no direct contract | Low (no relationship, but critical dependency) |

> **Note:** Since the team is solo, the Product Owner and Developer roles are held by the same person. Stakeholder reviews and decision checkpoints should be explicitly scheduled with the Education Platform Owner to avoid blind spots.

---

## 4. Current State (As-Is)

The existing online education platform teaches trading-related topics but has **no hands-on practice environment**. Learners currently rely on one of the following workarounds:

- Opening real accounts on live exchanges (Binance, local VN brokers) and risking real money while learning.
- Using third-party paper-trading tools (TradingView paper trading, external simulators) — disconnected from the course content, no instructor visibility, no integration with learning progress.
- Following instructor demonstrations without hands-on practice.

Pain points:

- **Financial risk:** Beginners lose real money while still learning.
- **Fragmented experience:** Learners switch between learning platform and external tools.
- **No instructor feedback loop:** Instructors cannot see learner trading activity, cannot design exercises around it.
- **No VN-market practice environment:** Most paper-trading tools focus on US/global markets; practicing with Vietnamese stocks is effectively unsupported.

---

## 5. Proposed Solution (To-Be)

A two-stage rollout:

**Stage 1 — Standalone POC:** A web application where any signed-up user can access a simulated spot trading environment. Users receive a virtual balance on signup, can simulate deposits/withdrawals through a fake gateway, place market and limit orders on supported crypto pairs, view a real-time order book and TradingView chart, and track trade history and portfolio value. Order execution prices are derived from live Binance market data — learners see realistic fills, spreads, and price movements without real-money exposure.

**Stage 2 — Embedded module in the education platform:** The same frontend is mounted inside the host Next.js application. Authentication is delegated to the host (SSO), and user accounts in the trading platform are linked to learner accounts in the education platform. Instructors (future) can issue exercises, see learner activity, and use the trading tool as part of structured coursework.

Key business-level capabilities:

- Virtual wallet per user per asset, with simulated deposit/withdrawal flows.
- Real-time market data (prices, order book, candlestick charts) sourced from external exchanges.
- Order placement: market and limit orders, with standard order lifecycle (new → partially filled / filled / cancelled).
- Trade history and portfolio valuation.
- Fee simulation (taker/maker fees applied to virtual balances so learners experience real trading costs).
- Architecture designed to support adding new asset classes (VN stocks) without disrupting existing services.

---

## 6. Scope

### 6.1 In Scope (MVP)

- User registration and authentication (standalone mode; SSO-ready design).
- Virtual wallet management (one wallet per user per asset).
- Simulated deposit and withdrawal flows (fake gateway — no on-chain interaction).
- Spot trading for a curated set of major crypto pairs (e.g., BTC/USDT, ETH/USDT, BNB/USDT).
- Two order types: **Market** and **Limit** (Good-Till-Cancelled).
- Order lifecycle: placement, partial fills, full fills, cancellation, expiration.
- Real-time order book display (derived or synthesized from external feed).
- TradingView Lightweight Charts integration with live OHLCV data.
- Fee schedule simulation (single tier for MVP — maker/taker rates).
- Trade history and wallet transaction audit log.
- Frontend designed as an embeddable Next.js module from day one.
- Backend designed as microservices with clean domain boundaries (Order, Matching Engine, Wallet, Market Data, User/Auth + API Gateway).

### 6.2 Out of Scope (MVP)

- **Real money:** No real deposits, no on-chain transactions, no fiat on/off-ramp integration, no custody of real assets.
- **Real order matching between external users:** Matching is simulated against live market prices; no peer-to-peer order book execution is guaranteed in MVP. (See note in Section 8 on matching model.)
- **Margin trading, futures, options, staking, lending.**
- **Vietnamese stock trading** (deferred to post-MVP, but architecture must accommodate it).
- **Advanced order types:** Stop-loss, take-profit, OCO, iceberg, etc.
- **Advanced TradingView features:** MVP uses Lightweight Charts only; Advanced Charting Library migration is post-MVP.
- **KYC/AML workflows** (not applicable — no real money).
- **Instructor dashboards, exercise assignments, leaderboards** (post-MVP — Stage 2+).
- **Mobile native app** (responsive web only).
- **Multi-language UI** (Vietnamese or English only for MVP — decision deferred).
- **Production-grade observability stack** at MVP scale (basic logging only; OpenTelemetry/Jaeger tracing on the roadmap but not required for MVP sign-off).

### 6.3 Assumptions

- Binance public market data APIs remain freely accessible at current rate limits throughout the MVP build.
- The host education platform can expose an SSO mechanism (OAuth2 / JWT) for Stage 2 integration.
- The solo developer has full discretion over tech stack and architecture decisions within the defined constraints.
- No regulatory body classifies paper-trading platforms as financial services in Vietnam for the purposes of this project — learners never hold real assets.
- Infrastructure hosting (cloud provider, domain, SSL, etc.) is available or will be provisioned; infrastructure cost is acceptable within an undefined-but-reasonable personal/project budget.

---

## 7. Functional Requirements (High-Level)

| ID | Requirement | Priority | Description |
|----|------------|----------|-------------|
| BR-001 | User account creation | Must | Users can register with email/password and log in to the platform. |
| BR-002 | Virtual balance on signup | Must | Each new user receives an initial virtual balance (quote currency, e.g., USDT) sufficient for practice. |
| BR-003 | Simulated deposit/withdrawal | Must | Users can simulate depositing and withdrawing assets via a fake gateway that credits/debits their virtual wallet. |
| BR-004 | Place market order | Must | Users can submit market buy/sell orders that execute immediately against live market prices. |
| BR-005 | Place limit order | Must | Users can submit limit buy/sell orders that rest until the market price reaches the limit. |
| BR-006 | Cancel open order | Must | Users can cancel limit orders that are not yet fully filled. |
| BR-007 | Order history | Must | Users can view all their past and current orders with status. |
| BR-008 | Trade history | Must | Users can view all executed trades with price, quantity, fee, and timestamp. |
| BR-009 | Wallet balance view | Must | Users can see total, available, and frozen balance per asset. |
| BR-010 | Wallet transaction audit log | Must | Every balance change is recorded with type, amount, reference, and timestamp. |
| BR-011 | Real-time price chart | Must | TradingView Lightweight Charts display live candlestick data for selected trading pairs. |
| BR-012 | Real-time order book display | Must | Users see a live order book for the selected pair (data may be synthesized from external feed in MVP). |
| BR-013 | Fee simulation | Must | Maker and taker fees are calculated and deducted from virtual balances per trade. |
| BR-014 | Real-time market price feed | Must | The platform ingests live price data from Binance and makes it available to all services that need it. |
| BR-015 | Embeddable frontend module | Must | The frontend can be embedded inside another Next.js application with minimal friction. |
| BR-016 | SSO integration readiness | Should | The User/Auth service supports delegating authentication to an external identity provider (host app) in Stage 2. |
| BR-017 | Multiple trading pair support | Should | The platform supports at least 3–5 crypto pairs at MVP; configurable without code changes. |
| BR-018 | VN stock market support | Could | Architecture accommodates adding Vietnamese equities post-MVP by plugging in SSI/TCBS data feed. |
| BR-019 | Instructor visibility into learner activity | Could | Future capability for instructors to review learner trading behavior. |
| BR-020 | Order expiration (Time-In-Force variants) | Could | Beyond GTC — IOC, FOK — deferred unless time permits. |

**Priority legend (MoSCoW):** Must = non-negotiable for MVP go-live; Should = important but not blocking; Could = nice-to-have if time/budget allows.

---

## 8. Non-Functional Requirements

| ID | Category | Requirement |
|----|----------|-------------|
| NFR-001 | Performance — order placement latency | Order submission-to-acknowledgement ≤ 500 ms at 95th percentile under expected load. |
| NFR-002 | Performance — market data latency | Price updates from Binance visible on user UI within 2 seconds (95th percentile). |
| NFR-003 | Scalability | Support 100–1,000 concurrent active users (Stage 2 target) without horizontal scaling of individual services being required at MVP, but architecture must allow independent scaling per service. |
| NFR-004 | Availability | Best-effort 99% uptime during the POC phase; no formal SLA committed until commercial intent is confirmed. |
| NFR-005 | Data consistency — wallet balance | Wallet balance operations must be strongly consistent (no negative balances, no double-spends). Near-real-time consistency is non-negotiable; eventual consistency is unacceptable for wallet state. |
| NFR-006 | Data consistency — trade history | Trade and order records must be durably persisted before acknowledgement. |
| NFR-007 | Auditability | Every change to a wallet balance must produce an immutable audit record (WalletTransaction) with source reference (order/trade ID). |
| NFR-008 | Security — authentication | Passwords hashed with modern algorithm (bcrypt/argon2); session tokens (JWT) have reasonable expiry; standard OWASP top-10 protections. |
| NFR-009 | Security — transport | All client-server traffic over HTTPS in production. |
| NFR-010 | Maintainability | Each microservice deployable independently; no shared database; no cross-service DB joins. |
| NFR-011 | Portability — frontend | Frontend module must mount inside a host Next.js application without conflicting dependencies or global styles. |
| NFR-012 | Observability (MVP floor) | Structured logs per service; basic health-check endpoints. Distributed tracing and metrics are roadmap items, not MVP blockers. |
| NFR-013 | External dependency resilience | If the external price feed (Binance) is unavailable, the platform degrades gracefully — existing positions remain visible, new orders may be temporarily rejected with a clear user-facing reason. |

**Note on the matching model (ties to BR-004, BR-005, NFR-005):** In MVP, matching is simulated against live external market prices — a learner's market order is treated as immediately filled at the current external best bid/ask (plus a simulated spread/fee), and limit orders are treated as filled when the external market price crosses the limit. This is explicitly *not* a peer-to-peer matching engine between learners in MVP. The Matching Engine service exists and owns this simulation logic, so that a true P2P matching model can be introduced in the future without disrupting other services.

---

## 9. Constraints

- **Budget:** Self-funded / undefined — infrastructure cost must stay at hobby/side-project tier through POC.
- **Timeline:** MVP target < 3 months from project start.
- **Team:** Solo developer; all design, implementation, testing, and deployment by one person.
- **Technology (mandated by project owner):**
  - Backend: Spring Boot 3.x, Java 21, Maven.
  - Frontend: React / Next.js (must be embeddable into existing Next.js host app).
  - Charting: TradingView Lightweight Charts (v5) — Advanced Library is a future consideration.
  - Databases: PostgreSQL (transactional), Redis (cache/sessions/rate limiting), TimescaleDB (OHLCV).
  - Messaging: Kafka (inter-service events).
  - Container: Docker / docker-compose.
  - Development host: Windows 11 on i5-14600KF / RTX 5070 / 32GB+ (developer workstation).
- **Architectural principles (self-imposed):**
  - Microservices with strict domain boundaries; no shared database across services.
  - Cross-service data synchronization via Kafka events (eventual consistency) except for wallet operations which require strong consistency.
  - Saga Pattern for distributed transactions with compensating actions.
  - Redis is cache/buffer only; PostgreSQL is source of truth.
- **Regulatory:** None applicable at MVP (no real money, no custody, no securities). If the embedded Stage 2 rollout triggers any educational-content regulations in Vietnam, those must be reviewed before embedding goes live — out of scope for this BRD.

---

## 10. Risks

| ID | Risk | Likelihood | Impact | Mitigation |
|----|------|-----------|--------|------------|
| R-001 | Solo developer bandwidth — 5 microservices + FE + integration in < 3 months is aggressive for one person | High | High | Ruthlessly defer anything not on the Must list. Complete one service end-to-end before starting the next (current approach: Market Data done, Wallet in progress). Accept that some Should/Could items will slip. |
| R-002 | Binance API rate limits or policy changes | Medium | High | Implement caching in Market Data Service (Redis). Respect documented rate limits. Design Market Data Service so the feed provider is swappable (interface abstraction). |
| R-003 | Binance API becomes unreachable from Vietnamese IP or is blocked | Medium | High | Abstract the feed provider interface. Plan a fallback (another public feed) before committing to heavy Binance-specific logic. |
| R-004 | Wallet consistency bugs (double-spend, negative balance, lost credit after trade) | Medium | Critical | Enforce wallet mutation only through service layer (not raw SQL). Use optimistic locking with pessimistic fallback. Full audit log in WalletTransaction. Heavy unit + integration testing on wallet flows before any other feature work. |
| R-005 | Learners gaming the simulation (e.g., exploiting latency between feed and execution to guarantee profits) | Medium | Medium | Add deterministic simulated spread/slippage on market orders so outcomes are not risk-free arbitrage. Document that this is a teaching tool, not a zero-lag trading simulator. |
| R-006 | Tight coupling between services emerges despite microservices intent (shared-library leakage, synchronous call chains) | Medium | Medium | Enforce the `exchange-common` shared library as *schemas and value objects only* — no business logic. Prefer async event-driven flows. Review each new synchronous call for necessity. |
| R-007 | Stage 2 embedding fails due to host app constraints not known today | Medium | Medium | Engage the education platform owner early with a spike on SSO and iframe/module mounting. Do not defer integration concerns until MVP is "done." |
| R-008 | Scope creep from "just add VN stocks" or "just add margin" before MVP is stable | Medium | High | BRD explicitly defers both. Any change requires BRD revision. Track temptation, do not act on it. |
| R-009 | External feed data quality (gaps, stale prices, incorrect values) causes visibly wrong user outcomes | Low | Medium | Basic sanity checks on incoming ticks (price jump thresholds, timestamp staleness). Log anomalies. Not critical to block on in MVP but should be tracked. |
| R-010 | Kafka / PostgreSQL / Redis / TimescaleDB operational complexity overwhelms a solo dev | Medium | High | Use docker-compose for local dev. Keep infrastructure minimal in POC (single-node everything). Do not introduce Kubernetes, service mesh, etc. at MVP. |
| R-011 | Wallet provisioning lost (BR-002): if the Wallet Service consumer is down — or misconfigured and not running — longer than Kafka topic retention (7 days), the `UserRegistered` event is deleted before wallets are created, leaving the user **permanently without wallets** (deposit/order/balance all fail). Already materialized in dev (a user registered before the consumer worked never got wallets). | Medium | High | Make provisioning recoverable independently of the event: idempotent **re-provision** lazily on first wallet access and/or a reconciliation pass (SR-024, SRS §8.6, appendix SR-W-005). Keep consumer downtime well under retention. |

---

## 11. Dependencies

**Internal:**
- Completion and stability of Market Data Service (already implemented) before wallet and order services can be meaningfully tested with real prices.
- `exchange-common` shared library (planned) — required by all services for consistent enums, value objects, and event schemas.
- Host education platform team — required for Stage 2 SSO design and embedding requirements.

**External:**
- **Binance public market data APIs** — REST for historical/candlestick data, WebSocket for live ticks (critical).
- **SSI / TCBS data feeds** — for post-MVP Vietnamese equities support (not required for MVP).
- **TradingView Lightweight Charts library** — open-source, no formal vendor relationship but a tracked dependency.

---

## 12. Timeline & Milestones

| Milestone | Target | Description |
|-----------|--------|-------------|
| M1 — Market Data Service | Complete (done) | Ingests Binance REST data, exposes TradingView-compatible endpoints, Redis caching. |
| M2 — Wallet Service | In progress | Entity layer, balance operations, audit log, dev-mode seeding. |
| M3 — User & Auth Service | Month 1 | Registration, login, JWT, SSO-ready abstraction. |
| M4 — Order Service | Month 2 | Order placement, cancellation, lifecycle, persistence. |
| M5 — Matching Engine (simulation mode) | Month 2 | Consume order events, simulate fills against external market prices, emit trade events. |
| M6 — API Gateway + frontend MVP | Month 3 | Next.js app with login, wallet view, order placement, TradingView chart, order book, trade history. |
| M7 — Integration spike: embed into host app | Month 3 (late) | Prove the frontend module mounts inside the education platform; validate SSO contract. |
| M8 — POC go-live (standalone) | End of Month 3 | Publicly (or privately) accessible POC for first test users. |

These are aggressive targets for a solo developer; slippage of 2–4 weeks is realistic and acceptable provided scope is held.

---

## 13. Approval

| Name | Role | Date | Signature |
|------|------|------|-----------|
| Haizz | Product Owner & Developer | | |
| (TBD) | Education Platform Owner | | |

---

## Appendix A — Reference Entities

The following entities have been defined in parallel with this BRD and are the basis for the subsequent SRS/System Design documents. They are listed here for cross-reference only; full definitions live in `SRS_Các_entity.md`.

- **User, Asset, TradingPair** — reference & catalog data.
- **Wallet, WalletTransaction** — balance state and audit log (owned by Wallet Service).
- **Order, Trade** — trading activity (owned by Order Service and Matching Engine respectively).
- **Candlestick (OHLCV)** — time-series market data (owned by Market Data Service, TimescaleDB).
- **FeeSchedule** — fee tier configuration.
- **DepositRecord, WithdrawalRecord** — simulated deposit/withdrawal tracking.

## Appendix B — Explicit Non-Goals (Anti-Scope)

To prevent scope drift, this project is explicitly **not**:

- A competitor to Binance or any live crypto exchange.
- A regulated financial service.
- A custodian of real crypto assets — ever.
- A high-frequency trading testbed (latencies target human-trader UX, not machine-trader UX).
- A multi-tenant SaaS product at MVP (architecture allows it, but MVP is single-tenant).
