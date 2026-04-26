package com.haizz.exchange.auth.infrastructure.kafka;

import java.time.Instant;
import java.util.UUID;

public record EventEnvelope<T>(
        String eventId,
        String eventType,
        int version,
        Instant timestamp,
        String source,
        String correlationId,
        T payload
) {
    public static <T> EventEnvelope<T> of(String eventType, T payload) {
        return new EventEnvelope<>(
                UUID.randomUUID().toString(),
                eventType,
                1,
                Instant.now(),
                "auth-service",
                null,
                payload
        );
    }
}
