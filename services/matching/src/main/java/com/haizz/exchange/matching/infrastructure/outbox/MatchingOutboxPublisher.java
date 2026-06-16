package com.haizz.exchange.matching.infrastructure.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haizz.exchange.common.event.EventEnvelope;
import com.haizz.exchange.matching.domain.MatchingOutbox;
import com.haizz.exchange.matching.infrastructure.persistence.MatchingOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Enqueues domain events into the matching outbox within the caller's DB
 * transaction. Each event is wrapped in an {@link EventEnvelope} before being
 * serialized, so both {@code trade.executed} and {@code matching.events.v1}
 * carry the standard envelope shape.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchingOutboxPublisher {

    private static final String SOURCE = "matching-engine";

    private final MatchingOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String eventType, String topic, String partitionKey, Object payload) {
        String correlationId = MDC.get("correlationId");
        EventEnvelope<Object> envelope = EventEnvelope.of(
                UUID.randomUUID().toString(), eventType, SOURCE, correlationId, payload);

        try {
            String json = objectMapper.writeValueAsString(envelope);
            MatchingOutbox outbox = MatchingOutbox.of(eventType, topic, partitionKey, partitionKey, json);
            outboxRepository.save(outbox);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize event type=" + eventType, e);
        }
    }
}
