package com.haizz.exchange.wallet.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "wallet_outbox")
@Getter
@Setter
@NoArgsConstructor
public class WalletOutbox {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT")
    private String payloadJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    public static WalletOutbox of(String eventType, UUID aggregateId, String payloadJson) {
        WalletOutbox o = new WalletOutbox();
        o.id = UUID.randomUUID();
        o.eventType = eventType;
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
}
