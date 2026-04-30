package com.haizz.exchange.marketdata.infrastructure.persistence;

import com.haizz.exchange.marketdata.domain.Candlestick;
import com.haizz.exchange.marketdata.domain.Interval;
import com.haizz.exchange.marketdata.domain.PairSymbol;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Slf4j
@Repository
@RequiredArgsConstructor
public class CandlestickJdbcRepository {

    private static final String UPSERT_SQL = """
            INSERT INTO candlesticks
                (pair_symbol, interval, open_time, open, high, low, close, volume, quote_volume, trade_count, close_time)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (pair_symbol, interval, open_time) DO UPDATE SET
                high         = EXCLUDED.high,
                low          = EXCLUDED.low,
                close        = EXCLUDED.close,
                volume       = EXCLUDED.volume,
                quote_volume = EXCLUDED.quote_volume,
                trade_count  = EXCLUDED.trade_count,
                close_time   = EXCLUDED.close_time,
                ingested_at  = NOW()
            WHERE candlesticks.close_time < EXCLUDED.close_time
            """;

    private static final String FIND_MAX_OPEN_TIME_SQL =
            "SELECT MAX(open_time) FROM candlesticks WHERE pair_symbol = ? AND interval = ?";

    private static final String QUERY_HISTORY_SQL = """
            SELECT open_time, open, high, low, close, volume, quote_volume, trade_count, close_time
            FROM candlesticks
            WHERE pair_symbol = ? AND interval = ? AND open_time >= ? AND open_time <= ?
            ORDER BY open_time ASC
            LIMIT ?
            """;

    private static final String FIND_MIN_OPEN_TIME_SQL =
            "SELECT MIN(open_time) FROM candlesticks WHERE pair_symbol = ? AND interval = ?";

    private final JdbcTemplate jdbc;

    public Mono<Void> upsertAll(List<Candlestick> candlesticks) {
        if (candlesticks.isEmpty()) return Mono.empty();
        return Mono.fromRunnable(() -> {
            var batchArgs = candlesticks.stream().map(c -> new Object[]{
                    c.pair().value(),
                    c.interval().getValue(),
                    Timestamp.from(c.openTime()),
                    c.open(),
                    c.high(),
                    c.low(),
                    c.close(),
                    c.volume(),
                    c.quoteVolume(),
                    c.tradeCount(),
                    Timestamp.from(c.closeTime())
            }).toList();
            jdbc.batchUpdate(UPSERT_SQL, batchArgs);
            log.debug("Upserted {} candlesticks", candlesticks.size());
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    public Mono<Optional<Instant>> findLatestOpenTime(PairSymbol pair, Interval interval) {
        return Mono.fromCallable(() -> {
            Timestamp ts = jdbc.queryForObject(FIND_MAX_OPEN_TIME_SQL,
                    Timestamp.class, pair.value(), interval.getValue());
            return Optional.ofNullable(ts).map(Timestamp::toInstant);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Optional<Instant>> findEarliestOpenTime(PairSymbol pair, Interval interval) {
        return Mono.fromCallable(() -> {
            Timestamp ts = jdbc.queryForObject(FIND_MIN_OPEN_TIME_SQL,
                    Timestamp.class, pair.value(), interval.getValue());
            return Optional.ofNullable(ts).map(Timestamp::toInstant);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<List<Candlestick>> findHistory(PairSymbol pair, Interval interval,
                                               Instant from, Instant to, int limit) {
        return Mono.fromCallable(() -> jdbc.query(QUERY_HISTORY_SQL,
                (rs, rowNum) -> new Candlestick(
                        pair,
                        interval,
                        rs.getTimestamp("open_time").toInstant(),
                        rs.getBigDecimal("open"),
                        rs.getBigDecimal("high"),
                        rs.getBigDecimal("low"),
                        rs.getBigDecimal("close"),
                        rs.getBigDecimal("volume"),
                        rs.getBigDecimal("quote_volume"),
                        rs.getInt("trade_count"),
                        rs.getTimestamp("close_time").toInstant()
                ),
                pair.value(), interval.getValue(),
                Timestamp.from(from), Timestamp.from(to), limit
        )).subscribeOn(Schedulers.boundedElastic());
    }
}
