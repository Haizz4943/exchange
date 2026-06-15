-- ============================================================
-- Order Service — initial schema
-- ============================================================

-- ──────────────────────────────────────────────────────────────
-- Orders
-- ──────────────────────────────────────────────────────────────
CREATE TABLE orders (
    id               UUID          PRIMARY KEY,
    client_order_id  UUID          NULL,
    user_id          UUID          NOT NULL,
    pair             VARCHAR(20)   NOT NULL,
    side             VARCHAR(4)    NOT NULL,
    type             VARCHAR(8)    NOT NULL,
    quantity         NUMERIC(36,18) NOT NULL CHECK (quantity > 0),
    limit_price      NUMERIC(36,18) NULL     CHECK (limit_price IS NULL OR limit_price > 0),
    time_in_force    VARCHAR(8)    NOT NULL DEFAULT 'GTC',
    state            VARCHAR(24)   NOT NULL,
    filled_quantity  NUMERIC(36,18) NOT NULL DEFAULT 0 CHECK (filled_quantity >= 0),
    avg_fill_price   NUMERIC(36,18) NULL,
    freeze_amount    NUMERIC(36,18) NOT NULL CHECK (freeze_amount >= 0),
    freeze_asset     VARCHAR(10)   NOT NULL,
    rejection_reason VARCHAR(200)  NULL,
    version          BIGINT        NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_limit_price_presence CHECK (
        (type = 'LIMIT'  AND limit_price IS NOT NULL) OR
        (type = 'MARKET' AND limit_price IS NULL)
    ),
    CONSTRAINT ck_filled_not_exceeds_qty CHECK (filled_quantity <= quantity)
);

CREATE UNIQUE INDEX uq_orders_user_client_order_id
    ON orders (user_id, client_order_id)
    WHERE client_order_id IS NOT NULL;

CREATE INDEX ix_orders_user_created
    ON orders (user_id, created_at DESC);

CREATE INDEX ix_orders_state_pair
    ON orders (state, pair)
    WHERE state IN ('OPEN', 'PARTIALLY_FILLED');

-- ──────────────────────────────────────────────────────────────
-- Transactional outbox
-- ──────────────────────────────────────────────────────────────
CREATE TABLE order_outbox (
    id            UUID         PRIMARY KEY,
    event_type    VARCHAR(50)  NOT NULL,
    aggregate_type VARCHAR(40) NOT NULL DEFAULT 'Order',
    aggregate_id  VARCHAR(64)  NOT NULL,
    topic         VARCHAR(60)  NULL,
    partition_key VARCHAR(64)  NULL,
    payload_json  JSONB        NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    published_at  TIMESTAMPTZ  NULL,
    attempts      INT          NOT NULL DEFAULT 0,
    last_error    TEXT         NULL
);

CREATE INDEX ix_order_outbox_unpublished
    ON order_outbox (created_at)
    WHERE published_at IS NULL;

-- ──────────────────────────────────────────────────────────────
-- Trading pairs (reference data)
-- ──────────────────────────────────────────────────────────────
CREATE TABLE trading_pairs (
    symbol       VARCHAR(20)   PRIMARY KEY,
    base_asset   VARCHAR(10)   NOT NULL,
    quote_asset  VARCHAR(10)   NOT NULL,
    tick_size    NUMERIC(36,18) NOT NULL CHECK (tick_size > 0),
    step_size    NUMERIC(36,18) NOT NULL CHECK (step_size > 0),
    min_notional NUMERIC(36,18) NOT NULL CHECK (min_notional > 0),
    enabled      BOOLEAN       NOT NULL DEFAULT TRUE,
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

-- ──────────────────────────────────────────────────────────────
-- Assets (reference data)
-- ──────────────────────────────────────────────────────────────
CREATE TABLE assets (
    symbol     VARCHAR(10)  PRIMARY KEY,
    name       VARCHAR(50)  NOT NULL,
    decimals   SMALLINT     NOT NULL,
    enabled    BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- ──────────────────────────────────────────────────────────────
-- Fee schedules (reference data)
-- ──────────────────────────────────────────────────────────────
CREATE TABLE fee_schedules (
    tier       VARCHAR(20)   PRIMARY KEY,
    maker_rate NUMERIC(10,6) NOT NULL,
    taker_rate NUMERIC(10,6) NOT NULL,
    active     BOOLEAN       NOT NULL DEFAULT FALSE
);

-- ============================================================
-- Seed reference data
-- ============================================================

INSERT INTO assets (symbol, name, decimals) VALUES
    ('USDT', 'Tether',        6),
    ('BTC',  'Bitcoin',       8),
    ('ETH',  'Ethereum',      8),
    ('BNB',  'Binance Coin',  8),
    ('SOL',  'Solana',        8),
    ('XRP',  'Ripple',        8);

INSERT INTO trading_pairs (symbol, base_asset, quote_asset, tick_size, step_size, min_notional) VALUES
    ('BTCUSDT', 'BTC', 'USDT', 0.01,   0.00001, 10),
    ('ETHUSDT', 'ETH', 'USDT', 0.01,   0.0001,  10),
    ('BNBUSDT', 'BNB', 'USDT', 0.01,   0.001,   10),
    ('SOLUSDT', 'SOL', 'USDT', 0.01,   0.001,   10),
    ('XRPUSDT', 'XRP', 'USDT', 0.0001, 1,       10);

INSERT INTO fee_schedules (tier, maker_rate, taker_rate, active) VALUES
    ('tier_0', 0.0010, 0.0010, TRUE);
