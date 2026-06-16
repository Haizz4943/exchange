-- ============================================================
-- Matching Engine Service — initial schema
-- ============================================================

-- ──────────────────────────────────────────────────────────────
-- Executed trades (one row per fill, per order side)
-- ──────────────────────────────────────────────────────────────
CREATE TABLE trades (
    id                 UUID          PRIMARY KEY,
    order_id           UUID          NOT NULL,
    user_id            UUID          NOT NULL,
    pair               VARCHAR(20)   NOT NULL,
    base_asset         VARCHAR(10)   NOT NULL,
    quote_asset        VARCHAR(10)   NOT NULL,
    side               VARCHAR(4)    NOT NULL,
    price              NUMERIC(36,18) NOT NULL CHECK (price > 0),
    quantity           NUMERIC(36,18) NOT NULL CHECK (quantity > 0),
    quote_amount       NUMERIC(36,18) NOT NULL CHECK (quote_amount > 0),
    fee_amount         NUMERIC(36,18) NOT NULL CHECK (fee_amount >= 0),
    fee_asset          VARCHAR(10)   NOT NULL,
    role               VARCHAR(5)    NOT NULL,
    external_trade_id  VARCHAR(64)   NULL,
    executed_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX ix_trades_user_executed ON trades (user_id, executed_at DESC);
CREATE INDEX ix_trades_order         ON trades (order_id);
CREATE INDEX ix_trades_pair_executed ON trades (pair, executed_at DESC);

-- ──────────────────────────────────────────────────────────────
-- Transactional outbox — note the per-row `topic` column, since
-- matching publishes to TWO topics (trade.executed + matching.events.v1).
-- ──────────────────────────────────────────────────────────────
CREATE TABLE matching_outbox (
    id             UUID         PRIMARY KEY,
    event_type     VARCHAR(50)  NOT NULL,
    aggregate_type VARCHAR(40)  NOT NULL DEFAULT 'Trade',
    aggregate_id   VARCHAR(64)  NOT NULL,
    topic          VARCHAR(60)  NOT NULL,
    partition_key  VARCHAR(64)  NOT NULL,
    payload_json   JSONB        NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    published_at   TIMESTAMPTZ  NULL,
    attempts       INT          NOT NULL DEFAULT 0,
    last_error     TEXT         NULL
);

CREATE INDEX ix_matching_outbox_unpublished ON matching_outbox (created_at) WHERE published_at IS NULL;

-- ──────────────────────────────────────────────────────────────
-- Fee schedules (maker/taker rates per tier)
-- ──────────────────────────────────────────────────────────────
CREATE TABLE fee_schedules (
    tier        VARCHAR(20)    PRIMARY KEY,
    maker_rate  NUMERIC(10,6)  NOT NULL,
    taker_rate  NUMERIC(10,6)  NOT NULL,
    active      BOOLEAN        NOT NULL DEFAULT FALSE
);

INSERT INTO fee_schedules (tier, maker_rate, taker_rate, active)
VALUES ('tier_0', 0.0010, 0.0010, TRUE);
