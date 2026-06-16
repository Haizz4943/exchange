package com.haizz.exchange.matching.infrastructure.kafka;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haizz.exchange.common.event.EventEnvelope;
import com.haizz.exchange.common.event.market.ExternalTradeObservedEvent;
import com.haizz.exchange.common.event.market.MarketDataFeedDegradedEvent;
import com.haizz.exchange.common.event.market.MarketDataFeedRecoveredEvent;
import com.haizz.exchange.matching.application.MatchDispatcher;
import com.haizz.exchange.matching.domain.FeedStatusRegistry;
import com.haizz.exchange.matching.infrastructure.index.PairExecutorRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code market-data.events.v1} ({@link EventEnvelope}-wrapped). External trades
 * are dispatched onto the pair executor to drive (phase-3) matching; feed-health events
 * update the {@link FeedStatusRegistry}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketDataEventsConsumer {

    private final ObjectMapper objectMapper;
    private final PairExecutorRegistry pairExecutorRegistry;
    private final MatchDispatcher matchDispatcher;
    private final FeedStatusRegistry feedStatusRegistry;

    @KafkaListener(
            topics = "${matching.kafka.market-data-events-topic:market-data.events.v1}",
            groupId = "matching-engine"
    )
    public void onMarketDataEvent(ConsumerRecord<String, String> record) {
        try {
            String eventType = peekEventType(record.value());
            switch (eventType) {
                case "ExternalTradeObservedEvent" -> handleExternalTrade(record.value());
                case "MarketDataFeedDegradedEvent" -> handleDegraded(record.value());
                case "MarketDataFeedRecoveredEvent" -> handleRecovered(record.value());
                default -> log.debug("Ignoring market-data event type={}", eventType);
            }
        } catch (Exception e) {
            log.error("Failed to process market-data event key={} offset={}",
                    record.key(), record.offset(), e);
        }
    }

    private void handleExternalTrade(String json) throws Exception {
        EventEnvelope<ExternalTradeObservedEvent> env = objectMapper.readValue(
                json, new TypeReference<EventEnvelope<ExternalTradeObservedEvent>>() {});
        ExternalTradeObservedEvent p = env.payload();
        pairExecutorRegistry.submit(p.pair(),
                () -> matchDispatcher.onExternalTrade(p.pair(), p.price(), p.quantity(), p.buyerIsMaker()));
    }

    private void handleDegraded(String json) throws Exception {
        EventEnvelope<MarketDataFeedDegradedEvent> env = objectMapper.readValue(
                json, new TypeReference<EventEnvelope<MarketDataFeedDegradedEvent>>() {});
        feedStatusRegistry.markDegraded(env.payload().pair());
    }

    private void handleRecovered(String json) throws Exception {
        EventEnvelope<MarketDataFeedRecoveredEvent> env = objectMapper.readValue(
                json, new TypeReference<EventEnvelope<MarketDataFeedRecoveredEvent>>() {});
        feedStatusRegistry.markRecovered(env.payload().pair());
    }

    /** Reads only the {@code eventType} field so we can route before binding the payload type. */
    private String peekEventType(String json) throws Exception {
        EventEnvelope<Object> env = objectMapper.readValue(
                json, new TypeReference<EventEnvelope<Object>>() {});
        return env.eventType();
    }
}
