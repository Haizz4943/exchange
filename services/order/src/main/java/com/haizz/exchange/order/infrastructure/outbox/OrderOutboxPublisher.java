package com.haizz.exchange.order.infrastructure.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haizz.exchange.common.event.EventEnvelope;
import com.haizz.exchange.order.domain.OrderOutbox;
import com.haizz.exchange.order.infrastructure.persistence.OrderOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Enqueues domain events into the transactional outbox. Must be called from
 * within the caller's DB transaction (MANDATORY) so the event row is committed
 * atomically with the order state change.
 *
 * <p>Payloads are wrapped in a shared {@link EventEnvelope} (with {@code eventType}
 * and {@code source}) so downstream consumers (Matching Engine, Gateway WS) can
 * discriminate event types on {@code orders.events.v1} — consistent with every
 * other topic in the system.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderOutboxPublisher {

    private static final String SOURCE = "order-service";

    private final OrderOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String eventType, UUID orderId, Object payload) {
        try {
            EventEnvelope<Object> envelope = EventEnvelope.of(
                    UUID.randomUUID().toString(), eventType, SOURCE, correlationId(), payload);
            String json = objectMapper.writeValueAsString(envelope);
            OrderOutbox outbox = OrderOutbox.of(eventType, orderId.toString(), json);
            outboxRepository.save(outbox);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to serialize outbox payload for eventType=" + eventType, e);
        }
    }

    private String correlationId() {
        String cid = MDC.get("correlationId");
        return cid != null ? cid : UUID.randomUUID().toString();
    }
}
