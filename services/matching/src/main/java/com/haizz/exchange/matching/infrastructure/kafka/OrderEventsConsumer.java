package com.haizz.exchange.matching.infrastructure.kafka;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haizz.exchange.common.enums.OrderSide;
import com.haizz.exchange.common.enums.OrderType;
import com.haizz.exchange.common.event.EventEnvelope;
import com.haizz.exchange.common.event.order.OrderCancelledEvent;
import com.haizz.exchange.common.event.order.OrderPlacedEvent;
import com.haizz.exchange.matching.application.OrderDispatcher;
import com.haizz.exchange.matching.domain.ResidentOrder;
import com.haizz.exchange.matching.infrastructure.index.PairExecutorRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Consumes {@code orders.events.v1} ({@link EventEnvelope}-wrapped) and dispatches each
 * order lifecycle event onto the order's pair executor so the in-memory index is mutated
 * single-threaded per pair.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventsConsumer {

    private final ObjectMapper objectMapper;
    private final PairExecutorRegistry pairExecutorRegistry;
    private final OrderDispatcher orderDispatcher;

    @KafkaListener(
            topics = "${matching.kafka.order-events-topic:orders.events.v1}",
            groupId = "matching-engine"
    )
    public void onOrderEvent(ConsumerRecord<String, String> record) {
        try {
            String eventType = peekEventType(record.value());
            switch (eventType) {
                case "OrderPlaced" -> handlePlaced(record.value());
                case "OrderCancelled" -> handleCancelled(record.value());
                default -> log.debug("Ignoring order event type={}", eventType);
            }
        } catch (Exception e) {
            log.error("Failed to process order event key={} offset={}",
                    record.key(), record.offset(), e);
        }
    }

    private void handlePlaced(String json) throws Exception {
        EventEnvelope<OrderPlacedEvent> env = objectMapper.readValue(
                json, new TypeReference<EventEnvelope<OrderPlacedEvent>>() {});
        OrderPlacedEvent p = env.payload();

        ResidentOrder ro = new ResidentOrder(
                p.orderId(),
                p.userId(),
                p.pair(),
                OrderSide.valueOf(p.side()),
                OrderType.valueOf(p.type()),
                p.quantity(),
                p.price(),               // null for MARKET
                p.placedAt(),
                BigDecimal.ZERO
        );
        pairExecutorRegistry.submit(p.pair(), () -> orderDispatcher.onOrderPlaced(ro));
    }

    private void handleCancelled(String json) throws Exception {
        EventEnvelope<OrderCancelledEvent> env = objectMapper.readValue(
                json, new TypeReference<EventEnvelope<OrderCancelledEvent>>() {});
        OrderCancelledEvent p = env.payload();
        pairExecutorRegistry.submit(p.pair(), () -> orderDispatcher.onOrderCancelled(p.orderId(), p.pair()));
    }

    /** Reads only the {@code eventType} field so we can route before binding the payload type. */
    private String peekEventType(String json) throws Exception {
        EventEnvelope<Object> env = objectMapper.readValue(
                json, new TypeReference<EventEnvelope<Object>>() {});
        return env.eventType();
    }
}
