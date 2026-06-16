package com.haizz.exchange.matching.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "matching_outbox")
@Getter
@NoArgsConstructor
public class MatchingOutbox {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(name = "aggregate_type", nullable = false, length = 40)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 64)
    private String aggregateId;

    @Column(name = "topic", nullable = false, length = 60)
    private String topic;

    @Column(name = "partition_key", nullable = false, length = 64)
    private String partitionKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_json", nullable = false, columnDefinition = "jsonb")
    private String payloadJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    public static MatchingOutbox of(String eventType, String topic, String partitionKey,
                                    String aggregateId, String payloadJson) {
        MatchingOutbox o = new MatchingOutbox();
        o.id = UUID.randomUUID();
        o.eventType = eventType;
        o.aggregateType = "Trade";
        o.topic = topic;
        o.partitionKey = partitionKey;
        o.aggregateId = aggregateId;
        o.payloadJson = payloadJson;
        o.createdAt = Instant.now();
        o.attempts = 0;
        return o;
    }

    public void incrementAttempts() {
        this.attempts++;
    }

    public void markPublished() {
        this.publishedAt = Instant.now();
    }

    public boolean isPublished() {
        return publishedAt != null;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }
}
