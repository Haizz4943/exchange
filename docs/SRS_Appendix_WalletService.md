# SRS Appendix — Wallet Service

**Parent Document:** `SRS.md` v1.0
**Service:** Wallet Service
**Status:** In Progress (entity layer complete)
**Owned Entities:** `Wallet`, `WalletTransaction`, `DepositRecord`, `WithdrawalRecord`

---

## 1. Purpose & Boundaries

The Wallet Service is the single source of truth for user balances. It owns the full lifecycle of virtual balances: creation on user registration, credit/debit on deposits, withdrawals, trades, freeze/unfreeze on orders, and immutable audit logging.

**This service is the most consistency-critical in the system.** All other services hold only references to wallet operations; no other service writes to wallet state.

### 1.1 Service Responsibilities

- Create wallets on user registration (one per asset).
- Issue initial USDT grant (10,000 USDT) to new users.
- Process simulated deposits (USDT-only in MVP).
- Process simulated withdrawals (any asset).
- Freeze/unfreeze balance on order placement/cancellation (synchronous HTTP API consumed by Order Service).
- Credit/debit balance on trade execution (consuming `trade.executed` Kafka events).
- Record every balance change as an immutable `WalletTransaction`.
- Expose read APIs for balances and transaction history.

### 1.2 Service Non-Responsibilities

- Order validation (Order Service).
- Price discovery (Market Data Service).
- Trade execution / fee computation (Matching Engine).
- Fee rate management (Matching Engine owns the fee schedule; Wallet applies the amounts sent in trade events).

---

## 2. Data Model

### 2.1 Entities

#### `Wallet`

| Field | Type | Notes |
|-------|------|-------|
| `walletId` | UUID | PK |
| `userId` | UUID | Logical FK to User; indexed |
| `assetCode` | VARCHAR(10) | e.g., "USDT", "BTC"; indexed |
| `totalBalance` | DECIMAL(36, 18) | Always `≥ 0` |
| `availableBalance` | DECIMAL(36, 18) | Always `≥ 0` |
| `frozenBalance` | DECIMAL(36, 18) | Always `≥ 0` |
| `version` | BIGINT | `@Version` for optimistic locking |
| `createdAt` | TIMESTAMP | UTC |
| `updatedAt` | TIMESTAMP | UTC |

**Unique constraint:** `(userId, assetCode)` — one wallet per user per asset.

**Invariants (DB-level CHECK constraints):**

- `totalBalance = availableBalance + frozenBalance`
- `availableBalance >= 0`
- `frozenBalance >= 0`
- `totalBalance >= 0` (implied by the other three)

#### `WalletTransaction`

Immutable audit record. No UPDATE or DELETE allowed at the repository layer.

| Field | Type | Notes |
|-------|------|-------|
| `txnId` | UUID | PK |
| `walletId` | UUID | FK → Wallet |
| `userId` | UUID | Denormalized for query convenience |
| `assetCode` | VARCHAR(10) | Denormalized |
| `type` | VARCHAR(30) | Enum — see §2.2 |
| `deltaAvailable` | DECIMAL(36, 18) | Signed change to availableBalance |
| `deltaFrozen` | DECIMAL(36, 18) | Signed change to frozenBalance |
| `deltaTotal` | DECIMAL(36, 18) | Signed change to totalBalance (= deltaAvailable + deltaFrozen) |
| `balanceAfterAvailable` | DECIMAL(36, 18) | Snapshot after this txn |
| `balanceAfterFrozen` | DECIMAL(36, 18) | Snapshot after |
| `balanceAfterTotal` | DECIMAL(36, 18) | Snapshot after |
| `referenceType` | VARCHAR(20) | Enum: USER, ORDER, TRADE, DEPOSIT, WITHDRAWAL |
| `referenceId` | VARCHAR(64) | E.g., userId, orderId, tradeId |
| `createdAt` | TIMESTAMP | UTC |

**Design note on delta columns:** Unlike a naive `amount` column, tracking three signed deltas makes every transaction type (including freeze/unfreeze where total is unchanged but available/frozen shift) expressible in a uniform schema. Downstream audit queries reconstruct balance history by cumulative sum of `delta*` columns.

### 2.2 `WalletTransaction.type` Enum

| Type | Meaning | Typical deltas |
|------|---------|----------------|
| `SIGNUP_GRANT` | Initial USDT credit on registration. | `deltaAvailable=+10000`, `deltaFrozen=0`, `deltaTotal=+10000` |
| `DEPOSIT` | Simulated deposit credit. | `deltaAvailable=+amount`, `deltaTotal=+amount`, `deltaFrozen=0` |
| `WITHDRAWAL` | Simulated withdrawal debit. | `deltaAvailable=-amount`, `deltaTotal=-amount`, `deltaFrozen=0` |
| `ORDER_FREEZE` | Move from available to frozen on order placement. | `deltaAvailable=-amount`, `deltaFrozen=+amount`, `deltaTotal=0` |
| `ORDER_UNFREEZE` | Move from frozen to available on order cancellation or leftover after fill. | `deltaAvailable=+amount`, `deltaFrozen=-amount`, `deltaTotal=0` |
| `TRADE_DEBIT` | Asset sent in a trade (consumed from frozen). | `deltaFrozen=-amount`, `deltaTotal=-amount`, `deltaAvailable=0` |
| `TRADE_CREDIT` | Asset received in a trade (credited to available, already net of fee). | `deltaAvailable=+netAmount`, `deltaTotal=+netAmount`, `deltaFrozen=0` |
| `FEE` | Fee recorded (informational; credit is already net of fee). | `deltaAvailable=0`, `deltaFrozen=0`, `deltaTotal=0`, amount stored in a metadata field — this type exists purely as an audit record; actual balance movement is captured in `TRADE_CREDIT`. |

**Design clarification on `FEE`:** Because fees are implicitly deducted from the received asset (buyer receives `filledQty - feeInBase`; seller receives `quoteValue - feeInQuote`), there is no separate balance debit for fee. The `FEE` transaction is written purely for auditability — it records the fee amount, fee asset, trade reference, but applies zero delta to balances. A future feature ("fee rebate for makers" or similar) can use this record without schema change.

#### `DepositRecord`

| Field | Type | Notes |
|-------|------|-------|
| `depositId` | UUID | PK |
| `userId` | UUID | Indexed |
| `assetCode` | VARCHAR(10) | MVP: always "USDT" |
| `amount` | DECIMAL(36, 18) | > 0; ≤ 100,000 for USDT in MVP |
| `status` | VARCHAR(20) | PENDING, CONFIRMED, FAILED, REJECTED |
| `walletTxnId` | UUID | FK → WalletTransaction, nullable until confirmed |
| `createdAt` | TIMESTAMP | |
| `confirmedAt` | TIMESTAMP | Nullable |
| `failureReason` | VARCHAR(255) | Nullable |

#### `WithdrawalRecord`

Same shape as `DepositRecord`, with `depositId → withdrawalId`. All assets are withdrawable (no USDT-only restriction).

### 2.3 Balance Invariants (System-Wide)

1. **Arithmetic invariant:** `totalBalance = availableBalance + frozenBalance` — enforced at DB level (CHECK constraint) and verified in application code after every write.
2. **Non-negative invariant:** `availableBalance ≥ 0` and `frozenBalance ≥ 0`. Enforced at DB level.
3. **Audit invariant:** Sum of `deltaTotal` for a wallet's `WalletTransactions` equals `totalBalance` - initial balance (0). Reconciled by a nightly job post-MVP.
4. **Atomicity invariant:** Every write to `Wallet` is accompanied by at least one `WalletTransaction` insert, in the same DB transaction. Enforced in service code.

---

## 3. Functional Requirements (Detailed)

### 3.1 Wallet Initialization on Registration

**SR-W-001:** When User & Auth Service completes user registration, it publishes a Kafka event `user.registered`. Wallet Service consumes this event and creates one `Wallet` per supported asset from the asset catalog.

**SR-W-002:** The USDT wallet is credited 10,000 USDT as initial grant. All other wallets are created with zero balance.

**SR-W-003:** The initial grant creates a `WalletTransaction` of type `SIGNUP_GRANT`, `referenceType=USER`, `referenceId=userId`.

**SR-W-004:** The `user.registered` consumer is idempotent — re-consuming the same event for the same `userId` does not create duplicate wallets or a second grant.

**Given/When/Then — happy path:**

- Given a new user with `userId=U1` has just registered and the asset catalog has USDT, BTC, ETH, BNB, SOL, XRP.
- When Wallet Service consumes `user.registered(userId=U1)`.
- Then:
  - Six `Wallet` rows are inserted (one per asset).
  - USDT wallet: `total=10000, available=10000, frozen=0`.
  - Other wallets: `total=0, available=0, frozen=0`.
  - One `WalletTransaction(type=SIGNUP_GRANT, walletId=<USDT wallet>, deltaAvailable=+10000, deltaTotal=+10000, deltaFrozen=0, referenceType=USER, referenceId=U1)` is inserted.

**Given/When/Then — duplicate event:**

- Given the above state already exists.
- When Wallet Service re-consumes `user.registered(userId=U1)` (e.g., Kafka redelivery).
- Then no new rows are created (the unique constraint on `(userId, assetCode)` rejects the insert; service catches the constraint violation and logs INFO "duplicate user.registered, ignoring"). The consumer commits the offset.

**SR-W-005 (provisioning resilience — traces SR-024):** The idempotent replay above is only safe **while the event is still on the topic** (Kafka retention, 7 days — SRS §4.4). If the Wallet consumer is down (or not yet running) longer than retention, the `UserRegistered` event is deleted and the user is left with **no wallets** — provisioning will never fire from the event again. To honor "every user has wallets" (SR-010/SR-011), provisioning must be **recoverable independently of the event**:
- **Lazy provision:** on first wallet access for a user with no wallet rows (read `GET /wallets/me`, deposit, withdraw, or internal `freeze`/`balance`), create the full wallet set idempotently (keyed by `userId`, USDT granted exactly once) before serving the request.
- **and/or Reconciliation:** a periodic pass that finds users (from Auth) with no wallets and provisions them.
- Both paths reuse the same idempotent initialization as the consumer (unique `(userId, assetCode)` + single `SIGNUP_GRANT`), so they are safe to run repeatedly and concurrently with a late event redelivery. Until implemented, the affected operations return `WALLET_NOT_FOUND` (404) — never a misleading balance error (see SRS §8.6).

### 3.2 Simulated Deposit (USDT-Only)

**SR-W-010:** `POST /api/v1/deposits` — authenticated endpoint for users.

Request:
```json
{
  "assetCode": "USDT",
  "amount": 5000,
  "clientRequestId": "<UUID>"
}
```

**SR-W-011:** Maximum deposit per transaction: 100,000 USDT. No aggregate limit in MVP.

**SR-W-012:** Deposit processing is synchronous and instant-confirm (no delay simulation).

**SR-W-013:** Idempotency via `clientRequestId` — duplicate requests within 60 seconds return the result of the first request.

**Processing (single DB transaction):**

1. Validate input: `amount > 0`, `amount ≤ 100000`, `assetCode = "USDT"`, `clientRequestId` is a valid UUID.
2. Check idempotency: if a `DepositRecord` exists with the same `(userId, clientRequestId)` within 60 seconds → return its result (HTTP 200).
3. Load USDT wallet for user with optimistic lock.
4. Insert `DepositRecord(status=PENDING)`.
5. Update wallet: `availableBalance += amount`, `totalBalance += amount`, `frozenBalance` unchanged.
6. Insert `WalletTransaction(type=DEPOSIT, deltaAvailable=+amount, deltaTotal=+amount, deltaFrozen=0, referenceType=DEPOSIT, referenceId=<depositId>)`.
7. Update `DepositRecord.status=CONFIRMED`, `confirmedAt=now`, `walletTxnId=<txnId>`.
8. Commit.
9. Return HTTP 200.

**Given/When/Then — happy path:**

- Given U1 USDT wallet `total=10000, available=10000, frozen=0`.
- When U1 POSTs `/api/v1/deposits` with `{assetCode: "USDT", amount: 5000, clientRequestId: "c1"}`.
- Then:
  - Response HTTP 200 with body `{depositId: "<uuid>", status: "CONFIRMED", wallet: {total: 15000, available: 15000, frozen: 0}}`.
  - `Wallet`: `total=15000, available=15000, frozen=0`.
  - `WalletTransaction`: type=DEPOSIT, deltaAvailable=+5000, deltaTotal=+5000, balanceAfterAvailable=15000, balanceAfterTotal=15000.
  - `DepositRecord`: status=CONFIRMED, walletTxnId set.

**Given/When/Then — amount exceeds limit:**

- Given U1 requests a deposit of 150,000 USDT.
- When the request is validated.
- Then HTTP 400, body `{errorCode: "DEPOSIT_AMOUNT_EXCEEDS_LIMIT", message: "Maximum deposit per transaction is 100,000 USDT.", limit: 100000}`. No DB changes.

**Given/When/Then — asset not supported:**

- Given U1 requests a deposit of 1 BTC.
- When the request is validated.
- Then HTTP 400, body `{errorCode: "DEPOSIT_ASSET_NOT_SUPPORTED", message: "Only USDT deposits are supported in this release."}`. No DB changes.

**Given/When/Then — duplicate clientRequestId:**

- Given U1 has already deposited 5000 USDT with `clientRequestId="c1"` successfully.
- When U1 POSTs another deposit with `clientRequestId="c1"` (e.g., retry after network timeout) within 60 seconds.
- Then HTTP 200 with the original `depositId` and wallet state. No new records created.
- When U1 POSTs another deposit with `clientRequestId="c1"` after 60 seconds have elapsed.
- Then HTTP 200 — a new deposit is processed (the key is treated as free after the window).

### 3.3 Simulated Withdrawal

**SR-W-020:** `POST /api/v1/withdrawals` — authenticated endpoint.

Request:
```json
{
  "assetCode": "USDT",
  "amount": 5000,
  "clientRequestId": "<UUID>"
}
```

**SR-W-021:** Withdrawal is supported for any asset the user holds. No aggregate or per-transaction cap in MVP.

**SR-W-022:** Withdrawal amount must not exceed `availableBalance`. Frozen balance cannot be withdrawn — user must cancel open orders first.

**SR-W-023:** Withdrawal processing is synchronous and instant-confirm.

**SR-W-024:** Idempotency via `clientRequestId`, same 60-second window as deposits.

**Processing (single DB transaction):**

1. Validate input (amount > 0, assetCode in asset catalog).
2. Check idempotency.
3. Load wallet with optimistic lock.
4. If `availableBalance < amount` → abort with `INSUFFICIENT_AVAILABLE_BALANCE`.
5. Insert `WithdrawalRecord(status=PENDING)`.
6. Update wallet: `availableBalance -= amount`, `totalBalance -= amount`.
7. Insert `WalletTransaction(type=WITHDRAWAL, deltaAvailable=-amount, deltaTotal=-amount, ...)`.
8. Update `WithdrawalRecord` to CONFIRMED.
9. Commit. Return HTTP 200.

**Given/When/Then — happy path:**

- Given U1 USDT wallet `total=20000, available=20000, frozen=0`.
- When U1 POSTs withdrawal of 5000 USDT.
- Then response HTTP 200; wallet `total=15000, available=15000, frozen=0`; `WalletTransaction(type=WITHDRAWAL, deltaAvailable=-5000)` inserted; `WithdrawalRecord(status=CONFIRMED)` inserted.

**Given/When/Then — exceeds available (the key scenario from BRD/SRS):**

- Given U1 USDT wallet `total=70000, available=10000, frozen=60000` (a limit buy holds the 60000).
- When U1 POSTs withdrawal of 20,000 USDT.
- Then HTTP 400, body `{errorCode: "INSUFFICIENT_AVAILABLE_BALANCE", available: 10000, requested: 20000, frozen: 60000, message: "Insufficient available balance. Cancel open orders to free frozen balance."}`. No DB changes.

**Given/When/Then — withdraw up to available:**

- Given the same state as above.
- When U1 POSTs withdrawal of 10,000 USDT.
- Then HTTP 200; wallet `total=60000, available=0, frozen=60000`. Invariant holds: 60000 = 0 + 60000. ✓

### 3.4 Internal Endpoints — Freeze / Unfreeze (Consumed by Order Service)

These endpoints are called synchronously from Order Service over HTTP during order placement and cancellation. They are **not exposed to end users** via the API Gateway.

#### 3.4.1 Freeze

**SR-W-030:** `POST /api/v1/wallets/internal/freeze`.

Request:
```json
{
  "userId": "U1",
  "assetCode": "USDT",
  "amount": 60000,
  "referenceType": "ORDER",
  "referenceId": "<orderId>"
}
```

**Processing (single DB transaction):**

1. Idempotency: check for an existing `WalletTransaction` with `(referenceType=ORDER, referenceId=<orderId>, type=ORDER_FREEZE)`. If found and `amount` matches → return current wallet state HTTP 200. If found but amount differs → HTTP 409 `FREEZE_CONFLICT`.
2. Load wallet with optimistic lock.
3. If `availableBalance < amount` → abort with `INSUFFICIENT_AVAILABLE_BALANCE`.
4. `availableBalance -= amount`, `frozenBalance += amount`, `totalBalance` unchanged.
5. Insert `WalletTransaction(type=ORDER_FREEZE, deltaAvailable=-amount, deltaFrozen=+amount, deltaTotal=0, referenceType=ORDER, referenceId=<orderId>)`.
6. Commit. Return HTTP 200 with new balances.

**SR-W-031 (concurrency):** If optimistic lock fails (version mismatch), the service retries up to 3 times with small backoff. On third failure, fall back to pessimistic lock (`SELECT ... FOR UPDATE`). If that also fails (lock timeout 5s), return HTTP 409 `CONCURRENT_MODIFICATION`.

**Given/When/Then — happy path:**

- Given U1 USDT wallet `total=10000, available=10000, frozen=0`.
- When Order Service calls freeze with `amount=6000, referenceId=O1`.
- Then wallet becomes `total=10000, available=4000, frozen=6000`. `WalletTransaction(type=ORDER_FREEZE, deltaAvailable=-6000, deltaFrozen=+6000, deltaTotal=0, referenceId=O1)` inserted.

**Given/When/Then — idempotent retry:**

- Given the above state.
- When Order Service retries freeze with `referenceId=O1, amount=6000` (same).
- Then wallet unchanged; HTTP 200 returned with current state. No new `WalletTransaction`.

**Given/When/Then — insufficient available:**

- Given U1 USDT wallet `total=10000, available=4000, frozen=6000`.
- When Order Service calls freeze with `amount=5000, referenceId=O2`.
- Then HTTP 400 `INSUFFICIENT_AVAILABLE_BALANCE`. No DB changes.

#### 3.4.2 Unfreeze

**SR-W-032:** `POST /api/v1/wallets/internal/unfreeze`.

Request:
```json
{
  "userId": "U1",
  "assetCode": "USDT",
  "amount": 6000,
  "referenceType": "ORDER",
  "referenceId": "<orderId>",
  "reason": "ORDER_CANCELLED"
}
```

`reason` is free-form for audit (e.g., `ORDER_CANCELLED`, `FILL_LEFTOVER_RELEASE`).

**Processing:**

1. Idempotency check on `(referenceType, referenceId, reason)`.
2. Load wallet with optimistic lock.
3. If `frozenBalance < amount` → HTTP 409 `INSUFFICIENT_FROZEN_BALANCE` (indicates upstream bug, should not happen in normal flow).
4. `frozenBalance -= amount`, `availableBalance += amount`, `totalBalance` unchanged.
5. Insert `WalletTransaction(type=ORDER_UNFREEZE, deltaFrozen=-amount, deltaAvailable=+amount, deltaTotal=0, referenceType=ORDER, referenceId=<orderId>)`.
6. Commit.

### 3.5 Trade Event Consumer (Kafka `trade.executed`)

**SR-W-040:** Wallet Service subscribes to Kafka topic `trade.executed` with consumer group `wallet-service`. Each event is processed exactly once, idempotency keyed by `tradeId`.

**SR-W-041:** In MVP (no P2P), each trade event involves one user (the one who placed the order). Post-MVP (when P2P matching is added), each event will identify both maker and taker users, and Wallet Service will process both wallets in a single transaction (requires both users' wallets to be loaded with consistent locking order to avoid deadlock).

#### 3.5.1 Trade Event Payload (consumed)

```json
{
  "tradeId": "<uuid>",
  "orderId": "<uuid>",
  "userId": "U1",
  "pair": "BTCUSDT",
  "baseAsset": "BTC",
  "quoteAsset": "USDT",
  "side": "BUY",
  "price": 60000,
  "quantity": 1.0,
  "quoteQuantity": 60000,
  "feeAmount": 0.001,
  "feeAsset": "BTC",
  "role": "MAKER",
  "executedAt": "2026-04-20T10:30:00Z"
}
```

#### 3.5.2 Processing — BUY fill (user receives base, pays quote)

Single DB transaction:

1. Idempotency: check for existing `WalletTransaction` with `referenceType=TRADE, referenceId=<tradeId>`. If exists → log INFO and commit offset without reprocessing.
2. Load user's quote wallet and base wallet (both with optimistic lock; load in alphabetical order by `assetCode` to prevent deadlock in multi-user future).
3. Compute: `quoteDebit = quoteQuantity` (the trade cost); `baseCredit = quantity - feeAmount` (net receipt after fee).
4. **Quote wallet updates:**
   - `frozenBalance -= quoteDebit` (consume from frozen).
   - `totalBalance -= quoteDebit`.
   - Insert `WalletTransaction(type=TRADE_DEBIT, deltaFrozen=-quoteDebit, deltaTotal=-quoteDebit, deltaAvailable=0, referenceType=TRADE, referenceId=<tradeId>)`.
5. **Leftover freeze release** (applies when actual trade cost < frozen amount, e.g., market buy with safety buffer):
   - Load the matching `OrderService` order state to determine how much was frozen for this order and how much remains after this fill.
   - If residual frozen > 0 and the order is now FILLED (or CANCELLED) → unfreeze the residual: `frozenBalance -= residual, availableBalance += residual`.
   - Insert `WalletTransaction(type=ORDER_UNFREEZE, deltaFrozen=-residual, deltaAvailable=+residual, deltaTotal=0, referenceType=ORDER, referenceId=<orderId>, reason="FILL_LEFTOVER_RELEASE")`.
   - Note: This step requires Wallet Service to know per-order residual freeze. See §3.5.4 for the chosen implementation approach.
6. **Base wallet updates:**
   - `availableBalance += baseCredit`.
   - `totalBalance += baseCredit`.
   - Insert `WalletTransaction(type=TRADE_CREDIT, deltaAvailable=+baseCredit, deltaTotal=+baseCredit, deltaFrozen=0, referenceType=TRADE, referenceId=<tradeId>)`.
7. **Fee audit record:**
   - Insert `WalletTransaction(type=FEE, all deltas=0, metadata.feeAmount=feeAmount, metadata.feeAsset=feeAsset, referenceType=TRADE, referenceId=<tradeId>)`.
   - This is purely informational — no balance movement (fee was implicitly deducted in step 6).
8. Commit. Publish `wallet.transaction` Kafka event (for any downstream auditors/analytics — log-only consumer in MVP).

#### 3.5.3 Processing — SELL fill (user sends base, receives quote)

Symmetric:

1. Idempotency check.
2. Load both wallets.
3. Compute: `baseDebit = quantity`; `quoteCredit = quoteQuantity - feeAmount`.
4. **Base wallet:** `frozenBalance -= baseDebit`, `totalBalance -= baseDebit`. Insert `TRADE_DEBIT`.
5. **Leftover release** (if applicable — sell orders freeze exact quantity so typically no residual, but this covers edge cases like market sell with future enhancements).
6. **Quote wallet:** `availableBalance += quoteCredit`, `totalBalance += quoteCredit`. Insert `TRADE_CREDIT`.
7. **Fee record** (informational).
8. Commit.

#### 3.5.4 Residual Freeze Release — Implementation Approach

**The challenge:** Market orders freeze a safety buffer (per BRL-006). After the fill, any unused reserve must be released. Wallet Service needs to know how much is residual.

**Chosen approach (MVP):** The `trade.executed` event includes a boolean `isFinalFill` and, if true, a `residualFrozenAmount` field populated by Matching Engine. Matching Engine knows both the original freeze (from order metadata) and the actual fill cost, so it computes and reports the leftover.

Updated event payload (extension):
```json
{
  ...,
  "isFinalFill": true,
  "residualFrozenAmount": 600,
  "residualAsset": "USDT"
}
```

**Alternative approach (rejected):** Wallet Service queries Order Service over HTTP to get order state. Rejected because it introduces synchronous cross-service call on the hot consumer path, violating the async-core-pipeline principle.

**Given/When/Then — limit BUY full fill (no residual):**

- Given U1 placed limit buy 1 BTC @ 60,000, freezing exactly 60,000 USDT.
- Pre-state: USDT `total=70000, available=10000, frozen=60000`; BTC `total=0, available=0, frozen=0`.
- When `trade.executed` arrives: `{tradeId: T1, orderId: O1, userId: U1, side: BUY, price: 60000, quantity: 1, feeAmount: 0.001, feeAsset: BTC, role: MAKER, isFinalFill: true, residualFrozenAmount: 0}`.
- Then:
  - USDT wallet: frozen `60000 → 0`; total `70000 → 10000`; available unchanged at 10000. Invariant: 10000 = 10000 + 0. ✓
  - BTC wallet: available `0 → 0.999`; total `0 → 0.999`; frozen 0. Invariant: 0.999 = 0.999 + 0. ✓
  - `WalletTransaction` inserts: one `TRADE_DEBIT` on USDT (deltaTotal=-60000, deltaFrozen=-60000); one `TRADE_CREDIT` on BTC (deltaTotal=+0.999, deltaAvailable=+0.999); one `FEE` record (amount=0.001 BTC, deltas=0).

**Given/When/Then — market BUY with residual release:**

- Given U1 placed market buy 1 BTC. Best ask was 60,000 at placement time. Frozen = `60000 × 1 × 1.0005 × 1.01 = 60,630.3` USDT (per BRL-006, capped to pair precision).
- Pre-state: USDT `total=70000, available=10000, frozen=60000` — wait, this isn't consistent because total must = available + frozen. Let's restate:
- Pre-state: USDT `total=70630.3, available=10000, frozen=60630.3`.
- Actual fill (walk-the-book): 0.5 BTC @ 60000, 0.3 @ 60001, 0.2 @ 60002. VWAP = 60000.7. With 0.05% slippage: 60030.7. Total quote cost = `1 × 60030.7 = 60030.7` USDT.
- Residual = 60630.3 - 60030.7 = 599.6 USDT.
- When trade event arrives with `isFinalFill=true, residualFrozenAmount=599.6, residualAsset=USDT`.
- Then:
  - USDT: `TRADE_DEBIT` deltaFrozen=-60030.7, deltaTotal=-60030.7 → new state: total=10599.6, available=10000, frozen=599.6.
  - Leftover release: `ORDER_UNFREEZE` deltaFrozen=-599.6, deltaAvailable=+599.6, deltaTotal=0 → new state: total=10599.6, available=10599.6, frozen=0.
  - BTC: `TRADE_CREDIT` deltaAvailable=+0.999 (1 BTC × 1.0 - 0.001 fee), deltaTotal=+0.999.
  - `FEE` record: 0.001 BTC.
  - Final: USDT `total=10599.6, available=10599.6, frozen=0`; BTC `total=0.999, available=0.999, frozen=0`. All invariants hold. ✓

**Given/When/Then — partial fill (limit order, multi-tick):**

- Given U1 placed limit buy 2 BTC @ 60,000, freezing 120,000 USDT. Pre-state: USDT `total=120000, available=0, frozen=120000` (user also deposited 120k for this).
- When first partial fill arrives: `{tradeId: T1, orderId: O1, side: BUY, price: 60000, quantity: 0.3, feeAmount: 0.0003 BTC, role: MAKER, isFinalFill: false}`.
- Then:
  - USDT: `TRADE_DEBIT` deltaFrozen=-18000, deltaTotal=-18000 → state: total=102000, available=0, frozen=102000.
  - BTC: `TRADE_CREDIT` deltaAvailable=+0.2997, deltaTotal=+0.2997 → state: total=0.2997, available=0.2997, frozen=0.
  - `FEE` record: 0.0003 BTC.
  - No leftover release (isFinalFill=false).

- When final fill arrives completing the remaining 1.7 BTC: `{tradeId: T2, ..., quantity: 1.7, feeAmount: 0.0017 BTC, isFinalFill: true, residualFrozenAmount: 0}`.
- Then:
  - USDT: `TRADE_DEBIT` deltaFrozen=-102000, deltaTotal=-102000 → state: total=0, available=0, frozen=0.
  - BTC: `TRADE_CREDIT` deltaAvailable=+1.6983 → state: total=1.998, available=1.998, frozen=0.
  - `FEE` record: 0.0017 BTC.
  - Order O1 transitions to FILLED.

### 3.6 Read Endpoints

**SR-W-050:** `GET /api/v1/wallets/me` — returns all wallets for the authenticated user.

Response:
```json
{
  "wallets": [
    {"assetCode": "USDT", "total": 10000, "available": 10000, "frozen": 0, "updatedAt": "..."},
    {"assetCode": "BTC", "total": 0.999, "available": 0.999, "frozen": 0, "updatedAt": "..."},
    ...
  ],
  "totalValueInUSDT": 70000.0
}
```

- `totalValueInUSDT` is computed by calling Market Data Service for current prices; if Market Data is unavailable, this field is omitted (no failure of the endpoint — wallets still returned).

**SR-W-051:** `GET /api/v1/wallet-transactions` — paginated transaction history.

Query parameters: `assetCode` (optional filter), `type` (optional filter), `from`, `to` (ISO 8601 timestamps), `page` (default 0), `size` (default 50, max 200).

Response: standard paginated JSON with transactions sorted by `createdAt` DESC.

**SR-W-052:** `GET /api/v1/deposits` and `GET /api/v1/withdrawals` — paginated lists of the user's deposit and withdrawal records.

### 3.7 Error Handling (Service-Specific)

| Error Code | HTTP Status | When |
|------------|-------------|------|
| `INSUFFICIENT_AVAILABLE_BALANCE` | 400 | Withdrawal or freeze exceeds available |
| `INSUFFICIENT_FROZEN_BALANCE` | 409 | Unfreeze requests more than frozen (indicates upstream bug) |
| `DEPOSIT_AMOUNT_EXCEEDS_LIMIT` | 400 | Deposit amount > 100,000 USDT |
| `DEPOSIT_ASSET_NOT_SUPPORTED` | 400 | Deposit asset != USDT |
| `WITHDRAWAL_ASSET_NOT_SUPPORTED` | 400 | Withdrawal asset not in catalog |
| `WALLET_NOT_FOUND` | 404 | The user has no wallet for the asset. This **can** happen for an authenticated user when provisioning was lost (the `UserRegistered` event expired from Kafka before the consumer ran — see SR-W-005, SRS §8.6). It is **recoverable**: the system re-provisions idempotently (SR-024), so this is a transient fallback, not a dead end. |
| `FREEZE_CONFLICT` | 409 | Same referenceId with different amount |
| `CONCURRENT_MODIFICATION` | 409 | Optimistic lock failed after all retries |
| `INVALID_AMOUNT_PRECISION` | 400 | Amount has more decimals than asset allows |

---

## 4. Concurrency Strategy

### 4.1 Optimistic Lock (default)

All wallet writes use JPA `@Version` optimistic locking. On version conflict, the service retries the transaction up to 3 times with exponential backoff (10 ms, 20 ms, 40 ms).

### 4.2 Pessimistic Lock (fallback)

After 3 optimistic-lock failures, the service escalates to pessimistic lock (`SELECT ... FOR UPDATE`) for that operation. This protects against high-contention paths (a single user firing many concurrent orders).

### 4.3 Lock Ordering (multi-wallet operations)

Trade event consumers must load two wallets (base and quote). To prevent deadlocks, wallets are always loaded in alphabetical order by `assetCode`. E.g., for BTC/USDT pair, BTC is loaded first, then USDT.

### 4.4 Kafka Consumer Concurrency

`trade.executed` consumer uses concurrency = 3 (three threads processing partitions in parallel). The topic is partitioned by `userId`, so all events for a single user go to the same partition → processed sequentially. This makes per-user trade processing naturally ordered and avoids intra-user contention at the consumer level.

---

## 5. External Interfaces

### 5.1 REST API (exposed via API Gateway)

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| GET | `/api/v1/wallets/me` | User JWT | List user's wallets |
| GET | `/api/v1/wallet-transactions` | User JWT | Paginated txn history |
| POST | `/api/v1/deposits` | User JWT | Submit simulated deposit |
| GET | `/api/v1/deposits` | User JWT | List user's deposits |
| POST | `/api/v1/withdrawals` | User JWT | Submit simulated withdrawal |
| GET | `/api/v1/withdrawals` | User JWT | List user's withdrawals |

### 5.2 Internal REST API (consumed by Order Service)

Not exposed at the Gateway. Reachable only within the Docker network.

| Method | Path | Purpose |
|--------|------|---------|
| POST | `/api/v1/wallets/internal/freeze` | Freeze balance for new order |
| POST | `/api/v1/wallets/internal/unfreeze` | Unfreeze (cancellation or residual release by Order Service) |
| GET | `/api/v1/wallets/internal/balance?userId&assetCode` | Check balance (used by Order Service for pre-validation) |

### 5.3 Kafka Consumers

| Topic | Consumer Group | Processing |
|-------|---------------|------------|
| `user.registered` | `wallet-service` | Create wallets, grant USDT |
| `trade.executed` | `wallet-service` | Apply trade debit/credit + fee record |

### 5.4 Kafka Producers

| Topic | Event | Purpose |
|-------|-------|---------|
| `wallet.transaction` | Every `WalletTransaction` insert | Audit/analytics stream (log-only consumer in MVP) |

---

## 6. Database & Persistence

- Database: **PostgreSQL 15+**.
- Dedicated database/schema for Wallet Service — no cross-service access.
- All monetary columns: `DECIMAL(36, 18)`.
- Indexes:
  - `Wallet(userId, assetCode)` — unique.
  - `WalletTransaction(userId, createdAt DESC)` — history queries.
  - `WalletTransaction(referenceType, referenceId)` — idempotency checks.
  - `DepositRecord(userId, createdAt DESC)`.
  - `WithdrawalRecord(userId, createdAt DESC)`.

### 6.1 Dev-Mode Seeding

Per userMemories, an `ApplicationRunner` with `@Profile("dev")` seeds test users and balances. Seeding **must go through the service layer**, not raw SQL, to ensure all invariants are upheld and `WalletTransaction` records are created.

---

## 7. Testing Requirements (Appendix-Specific)

The following tests are mandatory before Wallet Service is considered feature-complete:

1. **Unit tests** for every balance operation (freeze, unfreeze, deposit, withdraw, trade credit/debit). Each test asserts all three balance invariants hold after the operation.
2. **Concurrency tests:** 10 threads × 100 iterations each, concurrently placing freeze requests for the same wallet. Assert final state is consistent and no lost updates occurred.
3. **Idempotency tests:** Same `clientRequestId` and same `tradeId` submitted multiple times → exactly one effect.
4. **Invariant tests:** Randomized sequences of operations (1000+ operations per test) with invariant checks after each operation.
5. **Integration tests** with Order Service: end-to-end flow of order → freeze → trade → unfreeze leftover.
6. **Kafka consumer tests:** Simulated duplicate `trade.executed` events; assert exactly-once effect.

---

## 8. Deferred / Post-MVP

- Multi-user trade event handling (for P2P matching). Schema supports it; code path not implemented.
- Nightly reconciliation job verifying audit invariant.
- Wallet snapshotting for fast balance reads (not needed at MVP scale).
- Fee rebate / tiered fee schedule (current MVP uses single flat rate).
- Account freeze/unfreeze at user level (admin action to lock a user's entire wallet — not MVP).
- Asset deposit/withdrawal beyond USDT (deferred per SRS §3.2).
- On-chain simulation of confirmation blocks (kept instant in MVP per bro's decision).

---

**End of SRS_Appendix_WalletService.md**
