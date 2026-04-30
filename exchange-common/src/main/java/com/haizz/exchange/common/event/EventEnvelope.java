package com.haizz.exchange.common.event;

import java.time.Instant;

public record EventEnvelope<T>(
        String eventId,
        String eventType,
        int version,
        Instant timestamp,
        String source,
        String correlationId,
        T payload
) {
    public static <T> EventEnvelope<T> of(String eventId, String eventType, String source, String correlationId, T payload) {
        return new EventEnvelope<>(eventId, eventType, 1, Instant.now(), source, correlationId, payload);
    }
}
