-- Market Data Service — V3: outbox for durable events (ExternalTradeObserved, feed status)
-- Light outbox (no transactional business DB) — write-ahead log for Kafka publishing.
-- ExternalTradeObserved + feed events must survive Kafka outages.

CREATE TABLE IF NOT EXISTS market_data_outbox (
    id             UUID         PRIMARY KEY,
    event_type     VARCHAR(50)  NOT NULL,
    topic          VARCHAR(60)  NOT NULL,
    partition_key  VARCHAR(64)  NOT NULL,
    payload        TEXT         NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    published_at   TIMESTAMPTZ  NULL,
    attempts       INT          NOT NULL DEFAULT 0,
    last_error     TEXT         NULL
);

CREATE INDEX IF NOT EXISTS ix_md_outbox_unpublished
    ON market_data_outbox (created_at ASC)
    WHERE published_at IS NULL;

CREATE TABLE IF NOT EXISTS market_data_outbox_dead_letter (
    id             UUID         PRIMARY KEY,
    event_type     VARCHAR(50)  NOT NULL,
    topic          VARCHAR(60)  NOT NULL,
    partition_key  VARCHAR(64)  NOT NULL,
    payload        TEXT         NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL,
    failed_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    attempts       INT          NOT NULL,
    last_error     TEXT         NULL
);
