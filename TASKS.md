# Quy ước
- mã `TASK-000` tuần tự, duy nhất. Mô tả ghi mã requirement `SR-###`(từ [SRS.md](docs/SRS.md)).
- Việc chỉ có ở chi tiết appendix mà không có mã `SR-###` hoặc phát sinh từ bug -> ghi why: <lý do có task này> + reference nếu có.
- `coding-workflow §6` = bước End-to-end verification của skill `coding-workflow`.
- Trạng thái section:
  - **Active** (đang/cần làm)·
  - **Waiting On** (code có, 🧪 chờ verify E2E theo `coding-workflow §6`)
  - **Someday** (post-MVP/hoãn).
  - **Done** (đã verify, không bug).

# Tasks
## Active

- [ ] **TASK-006 — Reconciliation job provision user thiếu ví** - SR-024. Pass định kỳ: lấy user từ Auth, ai chưa có ví → provision idempotent (tái dùng init của consumer)
- [ ] **TASK-031 — Unit test mọi balance op, assert đủ 3 invariant sau mỗi op** - [§7.1](docs/SRS_Appendix_WalletService.md#7-testing-requirements-appendix-specific) (why: §7 Testing + Definition of Done bắt buộc trước feature-complete; không có mã SR)
- [ ] **TASK-032 — Concurrency test 10 threads × 100 freeze cùng 1 ví, assert không lost-update** - [§7.2](docs/SRS_Appendix_WalletService.md#7-testing-requirements-appendix-specific) (why: như TASK-031)
- [ ] **TASK-033 — Idempotency test: cùng clientRequestId / cùng tradeId → đúng 1 hiệu lực** - [§7.3](docs/SRS_Appendix_WalletService.md#7-testing-requirements-appendix-specific) (why: như TASK-031)
- [ ] **TASK-034 — Invariant test randomized 1000+ ops, check invariant sau mỗi op** - [§7.4](docs/SRS_Appendix_WalletService.md#7-testing-requirements-appendix-specific) (why: như TASK-031)
- [ ] **TASK-035 — Integration test với Order Service: order → freeze → trade → unfreeze leftover** - [§7.5](docs/SRS_Appendix_WalletService.md#7-testing-requirements-appendix-specific) (why: như TASK-031; cross-service)
- [ ] **TASK-036 — Kafka consumer test: dup `trade.executed` → exactly-once** - [§7.6](docs/SRS_Appendix_WalletService.md#7-testing-requirements-appendix-specific) (why: như TASK-031)

## Waiting On

- [ ] **TASK-001 — Consume `user.registered`, tạo 1 ví mỗi asset từ catalog** 🧪 - SR-010
  - Acceptance: [§3.1](docs/SRS_Appendix_WalletService.md#31-wallet-initialization-on-registration) happy — 6 ví (USDT/BTC/ETH/BNB/SOL/XRP)
- [ ] **TASK-002 — Grant 10.000 USDT cho ví USDT, các ví khác = 0** 🧪 - SR-011
- [ ] **TASK-003 — Ghi `WalletTransaction` type=SIGNUP_GRANT (referenceType=USER)** 🧪 - SR-011, SR-019
- [ ] **TASK-004 — Idempotent consumer: replay `user.registered` không tạo ví/grant trùng** 🧪 - SR-010, SR-024
  - Acceptance: [§3.1](docs/SRS_Appendix_WalletService.md#31-wallet-initialization-on-registration) duplicate-event — unique (userId, assetCode) chặn insert, log INFO, commit offset
- [ ] **TASK-007 — POST /api/v1/deposits (USDT-only, instant-confirm) + WalletTransaction DEPOSIT** 🧪 - SR-013, SR-014
  - Acceptance: [§3.2](docs/SRS_Appendix_WalletService.md#32-simulated-deposit-usdt-only) happy
- [ ] **TASK-008 — Validate amount ≤ 100.000, vượt → 400 DEPOSIT_AMOUNT_EXCEEDS_LIMIT** 🧪 - SR-013
- [ ] **TASK-009 — Reject asset ≠ USDT → 400 DEPOSIT_ASSET_NOT_SUPPORTED** 🧪 - SR-015
- [ ] **TASK-010 — Idempotency deposit theo clientRequestId trong 60s** 🧪 - (why: idempotency clientRequestId chỉ có ở chi tiết appendix, không có mã SR) [§3.2](docs/SRS_Appendix_WalletService.md#32-simulated-deposit-usdt-only) duplicate-clientRequestId
- [ ] **TASK-011 — POST /api/v1/withdrawals (mọi asset, instant) + WalletTransaction WITHDRAWAL** 🧪 - SR-016, SR-018
- [ ] **TASK-013 — Chặn rút > availableBalance → 400 INSUFFICIENT_AVAILABLE_BALANCE** 🧪 - SR-017, SR-020
  - Acceptance: [§3.3](docs/SRS_Appendix_WalletService.md#33-simulated-withdrawal) exceeds-available (frozen không rút được) + withdraw-up-to-available
- [ ] **TASK-014 — Idempotency withdrawal theo clientRequestId 60s** 🧪 - (why: như TASK-010) [§3.3](docs/SRS_Appendix_WalletService.md#33-simulated-withdrawal)
- [ ] **TASK-015 — POST internal/freeze happy + idempotent theo referenceId + 409 FREEZE_CONFLICT khi amount khác** 🧪 - SR-035
  - Acceptance: [§3.4.1](docs/SRS_Appendix_WalletService.md#341-freeze) happy + idempotent-retry
- [ ] **TASK-016 — Freeze khi available < amount → 400 INSUFFICIENT_AVAILABLE_BALANCE** 🧪 - SR-035, SR-020
- [ ] **TASK-017 — Optimistic lock retry 3× → fallback pessimistic → 409 CONCURRENT_MODIFICATION** 🧪 - SR-023, [§4.1](docs/SRS_Appendix_WalletService.md#41-optimistic-lock-default)/[§4.2](docs/SRS_Appendix_WalletService.md#42-pessimistic-lock-fallback)
- [ ] **TASK-018 — POST internal/unfreeze + 409 INSUFFICIENT_FROZEN_BALANCE** 🧪 - SR-035
- [ ] **TASK-019 — GET internal/balance?userId&assetCode** 🧪 - (why: helper nội bộ cho Order Service pre-validate balance, không có mã SR) [§5.2](docs/SRS_Appendix_WalletService.md#52-internal-rest-api-consumed-by-order-service)
- [ ] **TASK-020 — Consume `trade.executed`, idempotent theo tradeId** 🧪 - SR-056
- [ ] **TASK-021 — BUY fill: TRADE_DEBIT quote (từ frozen) + TRADE_CREDIT base (net fee)** 🧪 - SR-056, SR-019, [§3.5.2](docs/SRS_Appendix_WalletService.md#352-processing--buy-fill-user-receives-base-pays-quote)
  - Acceptance: [§3.5.4](docs/SRS_Appendix_WalletService.md#354-residual-freeze-release--implementation-approach) limit-BUY full fill (no residual)
- [ ] **TASK-022 — SELL fill: TRADE_DEBIT base + TRADE_CREDIT quote (net fee)** 🧪 - SR-056, SR-019, [§3.5.3](docs/SRS_Appendix_WalletService.md#353-processing--sell-fill-user-sends-base-receives-quote)
- [ ] **TASK-023 — Residual freeze release theo isFinalFill/residualFrozenAmount** 🧪 - SR-035, SR-056, [§3.5.4](docs/SRS_Appendix_WalletService.md#354-residual-freeze-release--implementation-approach)
  - Acceptance: [§3.5.4](docs/SRS_Appendix_WalletService.md#354-residual-freeze-release--implementation-approach) market-BUY residual + partial-fill multi-tick
- [ ] **TASK-024 — Ghi FEE audit record (mọi delta = 0, lưu feeAmount/feeAsset)** 🧪 - SR-019 (why: bản ghi audit fee — fee do Matching tính SR-057/058, Wallet chỉ ghi, không di chuyển số dư) [§3.5.2](docs/SRS_Appendix_WalletService.md#352-processing--buy-fill-user-receives-base-pays-quote)
- [ ] **TASK-025 — Lock ordering 2 ví theo assetCode (alphabetical) chống deadlock** 🧪 - SR-023 (why: impl detail concurrency khi 1 trade chạm cả ví base+quote; nền cho multi-user post-MVP) [§4.3](docs/SRS_Appendix_WalletService.md#43-lock-ordering-multi-wallet-operations)
- [ ] **TASK-026 — GET /api/v1/wallets/me + totalValueInUSDT (omit nếu Market Data down)** 🧪 - SR-021
- [ ] **TASK-027 — GET /api/v1/wallet-transactions phân trang + filter (assetCode/type/from/to)** 🧪 - SR-022
- [ ] **TASK-028 — GET /api/v1/deposits + /api/v1/withdrawals phân trang** 🧪 - (why: list deposit/withdrawal chỉ có ở appendix, ngoài SR-021/022) [§3.6](docs/SRS_Appendix_WalletService.md#36-read-endpoints)
- [ ] **TASK-029 — Publish `wallet.transaction` mỗi WalletTransaction insert** 🧪 - (why: stream audit/analytics, consumer log-only ở MVP; không có mã SR) [§5.4](docs/SRS_Appendix_WalletService.md#54-kafka-producers)
- [ ] **TASK-030 — Error envelope đủ mã + 400 INVALID_AMOUNT_PRECISION** 🧪 - (why: hợp đồng lỗi để FE/Gateway hiển thị đúng; gom mã lỗi, không phải 1 SR đơn lẻ) [§3.7](docs/SRS_Appendix_WalletService.md#37-error-handling-service-specific)
- [ ] **TASK-037 — Enforce invariant: DB CHECK + verify in-code sau mỗi write; atomicity Wallet+WalletTransaction trong 1 tx** 🧪 - SR-012, SR-020, [§2.3](docs/SRS_Appendix_WalletService.md#23-balance-invariants-system-wide)

## Someday

- [ ] **TASK-012 — Multi-user trade event (maker + taker 2 ví/1 tx, P2P)** - SR-056 (post-MVP; schema đã hỗ trợ, code path chưa làm)
- [ ] **TASK-038 — Nightly reconciliation job verify audit invariant** - SR-019 (why: kiểm Σ deltaTotal = totalBalance; post-MVP) [§2.3](docs/SRS_Appendix_WalletService.md#23-balance-invariants-system-wide) / [§8](docs/SRS_Appendix_WalletService.md#8-deferred--post-mvp)
- [ ] **TASK-039 — Wallet snapshotting cho read số dư nhanh** - (why: tối ưu read, không cần ở quy mô MVP) [§8](docs/SRS_Appendix_WalletService.md#8-deferred--post-mvp)
- [ ] **TASK-040 — Fee rebate / tiered fee schedule** - (why: MVP dùng 1 flat rate) [§8](docs/SRS_Appendix_WalletService.md#8-deferred--post-mvp)

## Done

- [x] **TASK-005 — Lazy provisioning ví khi truy cập lần đầu mà chưa có** - SR-024. `provisionIfMissing(userId)` idempotent (REQUIRES_NEW) gọi ở read `GET /wallets/me` + deposit + withdraw + internal freeze/balance, tái dùng init của consumer. E2E verified: vào ví + nạp tiền OK
  - Acceptance: [§3.1](docs/SRS_Appendix_WalletService.md#31-wallet-initialization-on-registration) — thay vì trả `WALLET_NOT_FOUND` vĩnh viễn khi event hết retention
