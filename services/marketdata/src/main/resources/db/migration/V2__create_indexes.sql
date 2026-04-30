-- Market Data Service — V2: indexes on candlesticks
-- Most common query: history endpoint queries by (pair_symbol, interval, open_time range)

CREATE INDEX IF NOT EXISTS ix_cs_pair_interval_time
    ON candlesticks (pair_symbol, interval, open_time DESC);
