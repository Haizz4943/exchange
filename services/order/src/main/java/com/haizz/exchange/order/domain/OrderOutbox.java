package com.haizz.exchange.order.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "order_outbox")
@Getter
@Setter
@NoArgsConstructor
public class OrderOutbox {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(name = "aggregate_type", nullable = false, length = 40)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 64)
    private String aggregateId;

    @Column(name = "topic", length = 60)
    private String topic;

    @Column(name = "partition_key", length = 64)
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

    public static OrderOutbox of(String eventType, String aggregateId, String payloadJson) {
        OrderOutbox o = new OrderOutbox();
        o.id = UUID.randomUUID();
        o.eventType = eventType;
        o.aggregateType = "Order";
        o.aggregateId = aggregateId;
        o.partitionKey = aggregateId;
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
}
