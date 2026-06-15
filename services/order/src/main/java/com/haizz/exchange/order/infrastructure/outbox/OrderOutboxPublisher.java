package com.haizz.exchange.order.infrastructure.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haizz.exchange.order.domain.OrderOutbox;
import com.haizz.exchange.order.infrastructure.persistence.OrderOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Enqueues domain events into the transactional outbox. Must be called from
 * within the caller's DB transaction (MANDATORY) so the event row is committed
 * atomically with the order state change.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderOutboxPublisher {

    private final OrderOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String eventType, UUID orderId, Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            OrderOutbox outbox = OrderOutbox.of(eventType, orderId.toString(), json);
            outboxRepository.save(outbox);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to serialize outbox payload for eventType=" + eventType, e);
        }
    }
}
