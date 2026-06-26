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
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.AbstractConsumerSeekAware;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Consumes {@code market-data.events.v1} ({@link EventEnvelope}-wrapped). External trades
 * are dispatched onto the pair executor to drive matching; feed-health events update the
 * {@link FeedStatusRegistry}.
 *
 * <p><b>Seek-to-end on assignment.</b> External trades are best-effort and non-retroactive
 * (see {@link MatchDispatcher}/{@code LimitOrderMatcher}): a trade is only ever applied
 * against the book <i>as it is now</i>. Resuming from the committed offset after a restart or
 * lag means replaying millions of stale external trades at obsolete prices, which makes
 * matching fill orders at wrong prices and can take ~98 min to catch up to live. So on every
 * partition assignment this listener seeks its market-data partitions to the end, discarding
 * the backlog and processing only live trades. This is scoped to THIS listener only — the
 * {@code orders.events.v1} / {@code matching.events.v1} listeners do not implement
 * {@link AbstractConsumerSeekAware} and keep their committed-offset (ordered, replayable)
 * semantics untouched. Toggle via {@code matching.kafka.market-data-seek-to-end-on-start}.
 */
@Slf4j
@Component
public class MarketDataEventsConsumer extends AbstractConsumerSeekAware {

    private final ObjectMapper objectMapper;
    private final PairExecutorRegistry pairExecutorRegistry;
    private final MatchDispatcher matchDispatcher;
    private final FeedStatusRegistry feedStatusRegistry;

    @Value("${matching.kafka.market-data-seek-to-end-on-start:true}")
    private boolean seekToEndOnStart;

    public MarketDataEventsConsumer(ObjectMapper objectMapper,
                                    PairExecutorRegistry pairExecutorRegistry,
                                    MatchDispatcher matchDispatcher,
                                    FeedStatusRegistry feedStatusRegistry) {
        this.objectMapper = objectMapper;
        this.pairExecutorRegistry = pairExecutorRegistry;
        this.matchDispatcher = matchDispatcher;
        this.feedStatusRegistry = feedStatusRegistry;
    }

    /**
     * On (re)assignment of market-data partitions, jump to the log end so the firehose backlog
     * of stale external trades is skipped and matching only ever sees live prices.
     */
    @Override
    public void onPartitionsAssigned(Map<TopicPartition, Long> assignments, ConsumerSeekCallback callback) {
        super.onPartitionsAssigned(assignments, callback);
        if (seekToEndOnStart && !assignments.isEmpty()) {
            callback.seekToEnd(assignments.keySet());
            log.info("market-data listener: seeking {} partition(s) to end on assignment — "
                    + "skipping stale external-trade backlog, matching from live", assignments.size());
        }
    }

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
