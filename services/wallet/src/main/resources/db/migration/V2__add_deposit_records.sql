-- Add deposit_records table (missed from initial schema)
CREATE TABLE IF NOT EXISTS deposit_records (
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

CREATE INDEX IF NOT EXISTS ix_deposit_user_created ON deposit_records (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_deposit_idempotency  ON deposit_records (user_id, client_request_id, created_at DESC);
