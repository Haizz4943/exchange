-- ============================================================
-- Wallet Service — initial schema
-- ============================================================

CREATE TABLE wallets (
    wallet_id          UUID           PRIMARY KEY,
    user_id            UUID           NOT NULL,
    asset_code         VARCHAR(10)    NOT NULL,
    total_balance      DECIMAL(36,18) NOT NULL DEFAULT 0,
    available_balance  DECIMAL(36,18) NOT NULL DEFAULT 0,
    frozen_balance     DECIMAL(36,18) NOT NULL DEFAULT 0,
    version            BIGINT         NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_wallets_user_asset UNIQUE (user_id, asset_code),
    CONSTRAINT chk_wallets_total     CHECK (total_balance     >= 0),
    CONSTRAINT chk_wallets_available CHECK (available_balance >= 0),
    CONSTRAINT chk_wallets_frozen    CHECK (frozen_balance    >= 0),
    CONSTRAINT chk_wallets_invariant CHECK (total_balance = available_balance + frozen_balance)
);

CREATE INDEX ix_wallets_user_id ON wallets (user_id);

-- ──────────────────────────────────────────────────────────────
-- Immutable audit log. No UPDATE or DELETE on this table.
-- ──────────────────────────────────────────────────────────────
CREATE TABLE wallet_transactions (
    txn_id                    UUID           PRIMARY KEY,
    wallet_id                 UUID           NOT NULL REFERENCES wallets(wallet_id),
    user_id                   UUID           NOT NULL,
    asset_code                VARCHAR(10)    NOT NULL,
    type                      VARCHAR(30)    NOT NULL,
    delta_available           DECIMAL(36,18) NOT NULL,
    delta_frozen              DECIMAL(36,18) NOT NULL,
    delta_total               DECIMAL(36,18) NOT NULL,
    balance_after_available   DECIMAL(36,18) NOT NULL,
    balance_after_frozen      DECIMAL(36,18) NOT NULL,
    balance_after_total       DECIMAL(36,18) NOT NULL,
    reference_type            VARCHAR(20)    NOT NULL,
    reference_id              VARCHAR(64)    NOT NULL,
    metadata                  VARCHAR(255)   NULL,
    created_at                TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_wt_type CHECK (type IN (
        'SIGNUP_GRANT','DEPOSIT','WITHDRAWAL',
        'ORDER_FREEZE','ORDER_UNFREEZE',
        'TRADE_DEBIT','TRADE_CREDIT','FEE'
    ))
);

CREATE INDEX ix_wt_user_created  ON wallet_transactions (user_id, created_at DESC);
CREATE INDEX ix_wt_ref           ON wallet_transactions (reference_type, reference_id);
CREATE INDEX ix_wt_wallet_id     ON wallet_transactions (wallet_id);

-- ──────────────────────────────────────────────────────────────
-- Deposit records
-- ──────────────────────────────────────────────────────────────
CREATE TABLE deposit_records (
    deposit_id        UUID           PRIMARY KEY,
    user_id           UUID           NOT NULL,
    asset_code        VARCHAR(10)    NOT NULL,
    amount            DECIMAL(36,18) NOT NULL,
    client_request_id VARCHAR(64)    NOT NULL,
    status            VARCHAR(20)    NOT NULL DEFAULT 'PENDING',
    wallet_txn_id     UUID           NULL REFERENCES wallet_transactions(txn_id),
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    confirmed_at      TIMESTAMPTZ    NULL,
    failure_reason    VARCHAR(255)   NULL,
    CONSTRAINT chk_deposit_status CHECK (status IN ('PENDING','CONFIRMED','FAILED','REJECTED'))
);

CREATE INDEX ix_deposit_user_created ON deposit_records (user_id, created_at DESC);
CREATE INDEX ix_deposit_idempotency  ON deposit_records (user_id, client_request_id, created_at DESC);

-- ──────────────────────────────────────────────────────────────
-- Withdrawal records
-- ──────────────────────────────────────────────────────────────
CREATE TABLE withdrawal_records (
    withdrawal_id     UUID           PRIMARY KEY,
    user_id           UUID           NOT NULL,
    asset_code        VARCHAR(10)    NOT NULL,
    amount            DECIMAL(36,18) NOT NULL,
    client_request_id VARCHAR(64)    NOT NULL,
    status            VARCHAR(20)    NOT NULL DEFAULT 'PENDING',
    wallet_txn_id     UUID           NULL REFERENCES wallet_transactions(txn_id),
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    confirmed_at      TIMESTAMPTZ    NULL,
    failure_reason    VARCHAR(255)   NULL,
    CONSTRAINT chk_withdrawal_status CHECK (status IN ('PENDING','CONFIRMED','FAILED','REJECTED'))
);

CREATE INDEX ix_withdrawal_user_created ON withdrawal_records (user_id, created_at DESC);
CREATE INDEX ix_withdrawal_idempotency  ON withdrawal_records (user_id, client_request_id, created_at DESC);

-- ──────────────────────────────────────────────────────────────
-- Transactional outbox
-- ──────────────────────────────────────────────────────────────
CREATE TABLE wallet_outbox (
    id            UUID        PRIMARY KEY,
    event_type    VARCHAR(40) NOT NULL,
    aggregate_id  UUID        NOT NULL,
    payload_json  TEXT        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    published_at  TIMESTAMPTZ NULL,
    attempts      INT         NOT NULL DEFAULT 0
);

CREATE INDEX ix_wallet_outbox_unpublished ON wallet_outbox (created_at) WHERE published_at IS NULL;
