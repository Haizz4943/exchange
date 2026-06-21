# Tasks

> Pilot: **Wallet Service** — break từ [SRS_Appendix_WalletService.md](docs/SRS_Appendix_WalletService.md).
> Quy ước: mã `TASK-000` (tuần tự, duy nhất). Mô tả ghi mã requirement **toàn cục `SR-###`**
> (từ [SRS.md](docs/SRS.md) §3 — **không** dùng kiểu per-service `SR-W-###`).
> Việc chỉ có ở chi tiết appendix mà không có mã `SR-###` toàn cục → ghi **(why: ...)** + link mục `§`. Task từ bug cũng ghi why.
> **Tham chiếu `§x.y`** = mục trong [SRS_Appendix_WalletService.md](docs/SRS_Appendix_WalletService.md), deep-link tới dòng bằng `#line`.
> `coding-workflow §6` = bước End-to-end verification của skill `coding-workflow` (không phải file repo nên không link được).
> Section = trạng thái: **Active** (đang/cần làm) · **Waiting On** (code có, 🧪 chờ verify E2E theo `coding-workflow §6`) ·
> **Someday** (post-MVP/hoãn) · **Done** (đã verify, không bug).
> Bug thực tế tạm bỏ qua theo yêu cầu — chưa gắn 🐞.

## Active

- [ ] **TASK-005 — Lazy provisioning ví khi truy cập lần đầu mà chưa có** - SR-024. Read/deposit/withdraw/freeze/balance cho user chưa có ví → tạo đủ bộ ví idempotent, USDT grant đúng 1 lần, trước khi phục vụ request
  - Acceptance: [§3.1](docs/SRS_Appendix_WalletService.md#130) — thay vì trả `WALLET_NOT_FOUND` vĩnh viễn khi event hết retention
- [ ] **TASK-006 — Reconciliation job provision user thiếu ví** - SR-024. Pass định kỳ: lấy user từ Auth, ai chưa có ví → provision idempotent (tái dùng init của consumer)
- [ ] **TASK-031 — Unit test mọi balance op, assert đủ 3 invariant sau mỗi op** - [§7.1](docs/SRS_Appendix_WalletService.md#595) (why: §7 Testing + Definition of Done bắt buộc trước feature-complete; không có mã SR toàn cục)
- [ ] **TASK-032 — Concurrency test 10 threads × 100 freeze cùng 1 ví, assert không lost-update** - [§7.2](docs/SRS_Appendix_WalletService.md#596) (why: như TASK-031)
- [ ] **TASK-033 — Idempotency test: cùng clientRequestId / cùng tradeId → đúng 1 hiệu lực** - [§7.3](docs/SRS_Appendix_WalletService.md#597) (why: như TASK-031)
- [ ] **TASK-034 — Invariant test randomized 1000+ ops, check invariant sau mỗi op** - [§7.4](docs/SRS_Appendix_WalletService.md#598) (why: như TASK-031)
- [ ] **TASK-035 — Integration test với Order Service: order → freeze → trade → unfreeze leftover** - [§7.5](docs/SRS_Appendix_WalletService.md#599) (why: như TASK-031; cross-service)
- [ ] **TASK-036 — Kafka consumer test: dup `trade.executed` → exactly-once** - [§7.6](docs/SRS_Appendix_WalletService.md#600) (why: như TASK-031)

## Waiting On

> 🧪 Code đã có (TODO.md đánh Wallet "Xong"), nhưng **chưa verify theo từng acceptance mịn** → giữ ở đây tới khi qua `coding-workflow §6`. Đây là verify-gate.

- [ ] **TASK-001 — Consume `user.registered`, tạo 1 ví mỗi asset từ catalog** 🧪 - SR-010
  - Acceptance: [§3.1](docs/SRS_Appendix_WalletService.md#130) happy — 6 ví (USDT/BTC/ETH/BNB/SOL/XRP)
- [ ] **TASK-002 — Grant 10.000 USDT cho ví USDT, các ví khác = 0** 🧪 - SR-011
- [ ] **TASK-003 — Ghi `WalletTransaction` type=SIGNUP_GRANT (referenceType=USER)** 🧪 - SR-011, SR-019
- [ ] **TASK-004 — Idempotent consumer: replay `user.registered` không tạo ví/grant trùng** 🧪 - SR-010, SR-024
  - Acceptance: [§3.1](docs/SRS_Appendix_WalletService.md#130) duplicate-event — unique (userId, assetCode) chặn insert, log INFO, commit offset
- [ ] **TASK-007 — POST /api/v1/deposits (USDT-only, instant-confirm) + WalletTransaction DEPOSIT** 🧪 - SR-013, SR-014
  - Acceptance: [§3.2](docs/SRS_Appendix_WalletService.md#161) happy
- [ ] **TASK-008 — Validate amount ≤ 100.000, vượt → 400 DEPOSIT_AMOUNT_EXCEEDS_LIMIT** 🧪 - SR-013
- [ ] **TASK-009 — Reject asset ≠ USDT → 400 DEPOSIT_ASSET_NOT_SUPPORTED** 🧪 - SR-015
- [ ] **TASK-010 — Idempotency deposit theo clientRequestId trong 60s** 🧪 - (why: idempotency clientRequestId chỉ có ở chi tiết appendix, không có mã SR toàn cục) [§3.2](docs/SRS_Appendix_WalletService.md#161) duplicate-clientRequestId
- [ ] **TASK-011 — POST /api/v1/withdrawals (mọi asset, instant) + WalletTransaction WITHDRAWAL** 🧪 - SR-016, SR-018
- [ ] **TASK-013 — Chặn rút > availableBalance → 400 INSUFFICIENT_AVAILABLE_BALANCE** 🧪 - SR-017, SR-020
  - Acceptance: [§3.3](docs/SRS_Appendix_WalletService.md#222) exceeds-available (frozen không rút được) + withdraw-up-to-available
- [ ] **TASK-014 — Idempotency withdrawal theo clientRequestId 60s** 🧪 - (why: như TASK-010) [§3.3](docs/SRS_Appendix_WalletService.md#222)
- [ ] **TASK-015 — POST internal/freeze happy + idempotent theo referenceId + 409 FREEZE_CONFLICT khi amount khác** 🧪 - SR-035
  - Acceptance: [§3.4.1](docs/SRS_Appendix_WalletService.md#277) happy + idempotent-retry
- [ ] **TASK-016 — Freeze khi available < amount → 400 INSUFFICIENT_AVAILABLE_BALANCE** 🧪 - SR-035, SR-020
- [ ] **TASK-017 — Optimistic lock retry 3× → fallback pessimistic → 409 CONCURRENT_MODIFICATION** 🧪 - SR-023, [§4.1](docs/SRS_Appendix_WalletService.md#517)/[§4.2](docs/SRS_Appendix_WalletService.md#521)
- [ ] **TASK-018 — POST internal/unfreeze + 409 INSUFFICIENT_FROZEN_BALANCE** 🧪 - SR-035
- [ ] **TASK-019 — GET internal/balance?userId&assetCode** 🧪 - (why: helper nội bộ cho Order Service pre-validate balance, không có mã SR toàn cục) [§5.2](docs/SRS_Appendix_WalletService.md#548)
- [ ] **TASK-020 — Consume `trade.executed`, idempotent theo tradeId** 🧪 - SR-056
- [ ] **TASK-021 — BUY fill: TRADE_DEBIT quote (từ frozen) + TRADE_CREDIT base (net fee)** 🧪 - SR-056, SR-019, [§3.5.2](docs/SRS_Appendix_WalletService.md#375)
  - Acceptance: [§3.5.4](docs/SRS_Appendix_WalletService.md#413) limit-BUY full fill (no residual)
- [ ] **TASK-022 — SELL fill: TRADE_DEBIT base + TRADE_CREDIT quote (net fee)** 🧪 - SR-056, SR-019, [§3.5.3](docs/SRS_Appendix_WalletService.md#400)
- [ ] **TASK-023 — Residual freeze release theo isFinalFill/residualFrozenAmount** 🧪 - SR-035, SR-056, [§3.5.4](docs/SRS_Appendix_WalletService.md#413)
  - Acceptance: [§3.5.4](docs/SRS_Appendix_WalletService.md#413) market-BUY residual + partial-fill multi-tick
- [ ] **TASK-024 — Ghi FEE audit record (mọi delta = 0, lưu feeAmount/feeAsset)** 🧪 - SR-019 (why: bản ghi audit fee — fee do Matching tính SR-057/058, Wallet chỉ ghi, không di chuyển số dư) [§3.5.2](docs/SRS_Appendix_WalletService.md#375)
- [ ] **TASK-025 — Lock ordering 2 ví theo assetCode (alphabetical) chống deadlock** 🧪 - SR-023 (why: impl detail concurrency khi 1 trade chạm cả ví base+quote; nền cho multi-user post-MVP) [§4.3](docs/SRS_Appendix_WalletService.md#525)
- [ ] **TASK-026 — GET /api/v1/wallets/me + totalValueInUSDT (omit nếu Market Data down)** 🧪 - SR-021
- [ ] **TASK-027 — GET /api/v1/wallet-transactions phân trang + filter (assetCode/type/from/to)** 🧪 - SR-022
- [ ] **TASK-028 — GET /api/v1/deposits + /api/v1/withdrawals phân trang** 🧪 - (why: list deposit/withdrawal chỉ có ở appendix, ngoài SR-021/022) [§3.6](docs/SRS_Appendix_WalletService.md#473)
- [ ] **TASK-029 — Publish `wallet.transaction` mỗi WalletTransaction insert** 🧪 - (why: stream audit/analytics, consumer log-only ở MVP; không có mã SR toàn cục) [§5.4](docs/SRS_Appendix_WalletService.md#566)
- [ ] **TASK-030 — Error envelope đủ mã + 400 INVALID_AMOUNT_PRECISION** 🧪 - (why: hợp đồng lỗi để FE/Gateway hiển thị đúng; gom mã lỗi, không phải 1 SR đơn lẻ) [§3.7](docs/SRS_Appendix_WalletService.md#499)
- [ ] **TASK-037 — Enforce invariant: DB CHECK + verify in-code sau mỗi write; atomicity Wallet+WalletTransaction trong 1 tx** 🧪 - SR-012, SR-020, [§2.3](docs/SRS_Appendix_WalletService.md#119)

## Someday

- [ ] **TASK-012 — Multi-user trade event (maker + taker 2 ví/1 tx, P2P)** - SR-056 (post-MVP; schema đã hỗ trợ, code path chưa làm)
- [ ] **TASK-038 — Nightly reconciliation job verify audit invariant** - SR-019 (why: kiểm Σ deltaTotal = totalBalance; post-MVP) [§2.3](docs/SRS_Appendix_WalletService.md#119) / [§8](docs/SRS_Appendix_WalletService.md#604)
- [ ] **TASK-039 — Wallet snapshotting cho read số dư nhanh** - (why: tối ưu read, không cần ở quy mô MVP) [§8](docs/SRS_Appendix_WalletService.md#604)
- [ ] **TASK-040 — Fee rebate / tiered fee schedule** - (why: MVP dùng 1 flat rate) [§8](docs/SRS_Appendix_WalletService.md#604)

## Done
