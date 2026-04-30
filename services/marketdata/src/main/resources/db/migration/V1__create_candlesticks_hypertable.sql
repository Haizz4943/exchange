-- Market Data Service — V1: candlesticks table + TimescaleDB hypertable
-- Uses NUMERIC(36,18) for all monetary values per DEV_GUIDE §8.3
-- Primary key: (pair_symbol, interval, open_time) — unique bar per pair+interval+time

CREATE TABLE IF NOT EXISTS candlesticks (
    pair_symbol  VARCHAR(20)      NOT NULL,
    interval     VARCHAR(5)       NOT NULL,
    open_time    TIMESTAMPTZ      NOT NULL,
    open         NUMERIC(36, 18)  NOT NULL,
    high         NUMERIC(36, 18)  NOT NULL,
    low          NUMERIC(36, 18)  NOT NULL,
    close        NUMERIC(36, 18)  NOT NULL,
    volume       NUMERIC(36, 18)  NOT NULL,
    quote_volume NUMERIC(36, 18)  NOT NULL,
    trade_count  INTEGER          NOT NULL,
    close_time   TIMESTAMPTZ      NOT NULL,
    ingested_at  TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    PRIMARY KEY (pair_symbol, interval, open_time)
);

-- Convert to TimescaleDB hypertable, partitioned by open_time
-- chunk_time_interval = 7 days keeps chunk sizes manageable for 6 intervals × 5 pairs
SELECT create_hypertable(
    'candlesticks',
    'open_time',
    chunk_time_interval => INTERVAL '7 days',
    if_not_exists       => TRUE
);
