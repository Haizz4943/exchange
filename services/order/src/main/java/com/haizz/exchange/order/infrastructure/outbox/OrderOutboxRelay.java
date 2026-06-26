package com.haizz.exchange.order.infrastructure.outbox;

import com.haizz.exchange.order.config.AppProperties;
import com.haizz.exchange.order.domain.Order;
import com.haizz.exchange.order.domain.OrderOutbox;
import com.haizz.exchange.order.domain.OrderState;
import com.haizz.exchange.order.infrastructure.persistence.OrderOutboxRepository;
import com.haizz.exchange.order.infrastructure.persistence.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderOutboxRelay {

    private static final int MAX_ATTEMPTS = 10;

    private final OrderOutboxRepository outboxRepository;
    private final OrderRepository orderRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final AppProperties appProperties;

    @Scheduled(fixedDelayString = "${order.outbox.relay-fixed-delay-ms:100}")
    @Transactional
    public void relay() {
        int batchSize = appProperties.outbox().relayBatchSize();
        List<OrderOutbox> pending = outboxRepository.findPendingEvents(
                PageRequest.of(0, batchSize));

        for (OrderOutbox event : pending) {
            event.incrementAttempts();

            if (event.getAttempts() > MAX_ATTEMPTS) {
                log.error("Outbox event id={} exceeded max attempts — skipping", event.getId());
                continue;
            }

            try {
                String topic = resolveTopic(event.getEventType());
                String key = event.getPartitionKey() != null
                        ? event.getPartitionKey()
                        : event.getAggregateId();
                kafkaTemplate.send(topic, key, event.getPayloadJson()).get();
                event.markPublished();
                log.info("Published outbox event type={} id={}", event.getEventType(), event.getId());
                // Self-transition NEW → OPEN on publish-ack of OrderPlaced
                // (SRS_Appendix_OrderService §5 / SR-ORDER-EDGE-006). Same tx as
                // markPublished so the two commit atomically.
                if ("OrderPlaced".equals(event.getEventType())) {
                    markOrderOpen(event.getAggregateId());
                }
            } catch (Exception e) {
                event.setLastError(e.getMessage());
                log.error("Failed to publish outbox event id={} attempt={}",
                        event.getId(), event.getAttempts(), e);
            }

            outboxRepository.save(event);
        }
    }

    /**
     * Moves a freshly-published order onto the book: NEW → OPEN. Only a NEW order is
     * transitioned — if the order has already left NEW (e.g. the user cancelled it →
     * CANCEL_REQUESTED within the relay window, or it is already OPEN from a re-relay)
     * we skip silently rather than letting {@link Order#markOpen()} throw.
     */
    private void markOrderOpen(String aggregateId) {
        Optional<Order> maybe = orderRepository.findById(UUID.fromString(aggregateId));
        if (maybe.isEmpty()) {
            log.warn("OrderPlaced published but order id={} not found — cannot mark OPEN", aggregateId);
            return;
        }
        Order order = maybe.get();
        if (order.getState() != OrderState.NEW) {
            return;
        }
        order.markOpen();
        orderRepository.save(order);
        log.info("Order id={} transitioned NEW → OPEN on publish-ack", aggregateId);
    }

    private String resolveTopic(String eventType) {
        return switch (eventType) {
            case "OrderPlaced", "OrderCancelled" -> appProperties.kafka().orderEventsTopic();
            default -> throw new IllegalArgumentException("Unknown event type: " + eventType);
        };
    }
}
