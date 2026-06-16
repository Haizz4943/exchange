package com.haizz.exchange.order.infrastructure.kafka;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haizz.exchange.common.event.EventEnvelope;
import com.haizz.exchange.common.event.order.OrderCancelledEvent;
import com.haizz.exchange.common.event.order.OrderFilledEvent;
import com.haizz.exchange.common.event.order.OrderPartiallyFilledEvent;
import com.haizz.exchange.order.application.ProcessFillEventUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes order-lifecycle events emitted by the Matching Engine on
 * {@code matching.events.v1} and applies fills/cancellations to the local order
 * aggregate (SR-042). Mirrors the wallet service's envelope-deserialization +
 * fail-soft pattern: a malformed/unknown event is logged and skipped, never
 * crashing the listener.
 *
 * <p><b>STUB:</b> handlers currently delegate to {@link ProcessFillEventUseCase},
 * which logs and TODOs rather than mutating orders — the Matching Engine and the
 * final event shapes are not built yet. See {@code DECISIONS.md} (Phase 5).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventConsumer {

    private final ProcessFillEventUseCase processFillEventUseCase;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "${order.kafka.matching-events-topic:matching.events.v1}",
            groupId = "order-service",
            concurrency = "1"
    )
    public void onMatchingEvent(ConsumerRecord<String, String> record) {
        try {
            // Peek at the envelope to learn the eventType, then deserialize the
            // payload to the matching concrete event record.
            String eventType = objectMapper
                    .readValue(record.value(), new TypeReference<EventEnvelope<Object>>() {})
                    .eventType();

            switch (eventType) {
                case "OrderPartiallyFilled" -> {
                    EventEnvelope<OrderPartiallyFilledEvent> env = objectMapper.readValue(
                            record.value(),
                            new TypeReference<EventEnvelope<OrderPartiallyFilledEvent>>() {});
                    processFillEventUseCase.onPartiallyFilled(env.payload());
                }
                case "OrderFilled" -> {
                    EventEnvelope<OrderFilledEvent> env = objectMapper.readValue(
                            record.value(),
                            new TypeReference<EventEnvelope<OrderFilledEvent>>() {});
                    processFillEventUseCase.onFilled(env.payload());
                }
                case "OrderCancelled" -> {
                    EventEnvelope<OrderCancelledEvent> env = objectMapper.readValue(
                            record.value(),
                            new TypeReference<EventEnvelope<OrderCancelledEvent>>() {});
                    processFillEventUseCase.onCancelled(env.payload());
                }
                default -> log.debug("Ignoring unknown matching event type={}", eventType);
            }
        } catch (Exception e) {
            log.error("Failed to process matching event key={} offset={}",
                    record.key(), record.offset(), e);
        }
    }
}
