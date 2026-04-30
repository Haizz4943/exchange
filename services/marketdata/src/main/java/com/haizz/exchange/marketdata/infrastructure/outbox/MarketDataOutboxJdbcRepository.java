package com.haizz.exchange.marketdata.infrastructure.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class MarketDataOutboxJdbcRepository {

    private static final String FETCH_UNPUBLISHED = """
            SELECT id, event_type, topic, partition_key, payload, attempts, created_at, published_at, last_error
            FROM market_data_outbox
            WHERE published_at IS NULL
            ORDER BY created_at ASC
            LIMIT ?
            """;

    private static final String INSERT = """
            INSERT INTO market_data_outbox (id, event_type, topic, partition_key, payload)
            VALUES (?, ?, ?, ?, ?)
            """;

    private static final String MARK_PUBLISHED =
            "UPDATE market_data_outbox SET published_at = NOW() WHERE id = ?";

    private static final String INCREMENT_ATTEMPTS =
            "UPDATE market_data_outbox SET attempts = attempts + 1, last_error = ? WHERE id = ?";

    private static final String MOVE_TO_DEAD_LETTER = """
            INSERT INTO market_data_outbox_dead_letter
                (id, event_type, topic, partition_key, payload, created_at, attempts, last_error)
            SELECT id, event_type, topic, partition_key, payload, created_at, attempts, last_error
            FROM market_data_outbox WHERE id = ?;
            DELETE FROM market_data_outbox WHERE id = ?;
            """;

    private final JdbcTemplate jdbc;

    public void save(MarketDataOutboxEntry entry) {
        jdbc.update(INSERT, entry.id(), entry.eventType(), entry.topic(),
                entry.partitionKey(), entry.payload());
    }

    public List<MarketDataOutboxEntry> fetchUnpublished(int limit) {
        return jdbc.query(FETCH_UNPUBLISHED,
                (rs, rowNum) -> {
                    Timestamp publishedAt = rs.getTimestamp("published_at");
                    return new MarketDataOutboxEntry(
                            UUID.fromString(rs.getString("id")),
                            rs.getString("event_type"),
                            rs.getString("topic"),
                            rs.getString("partition_key"),
                            rs.getString("payload"),
                            rs.getInt("attempts"),
                            rs.getTimestamp("created_at").toInstant(),
                            publishedAt != null ? publishedAt.toInstant() : null,
                            rs.getString("last_error")
                    );
                },
                limit);
    }

    public void markPublished(UUID id) {
        jdbc.update(MARK_PUBLISHED, id);
    }

    public void incrementAttempts(UUID id, String error) {
        jdbc.update(INCREMENT_ATTEMPTS, error, id);
    }

    public void moveToDeadLetter(UUID id) {
        jdbc.update("INSERT INTO market_data_outbox_dead_letter " +
                "(id, event_type, topic, partition_key, payload, created_at, attempts, last_error) " +
                "SELECT id, event_type, topic, partition_key, payload, created_at, attempts, last_error " +
                "FROM market_data_outbox WHERE id = ?", id);
        jdbc.update("DELETE FROM market_data_outbox WHERE id = ?", id);
    }
}
