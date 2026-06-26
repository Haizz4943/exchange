package com.haizz.exchange.matching.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haizz.exchange.matching.application.MatchDispatcher;
import com.haizz.exchange.matching.domain.FeedStatusRegistry;
import com.haizz.exchange.matching.infrastructure.index.PairExecutorRegistry;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.ConsumerSeekAware.ConsumerSeekCallback;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Verifies the market-data listener seeks its assigned partitions to the log end on assignment
 * (skipping the stale external-trade backlog) when enabled, and leaves the consumer at its
 * committed offset when disabled.
 */
class MarketDataEventsConsumerSeekTest {

    private MarketDataEventsConsumer newConsumer(boolean seekToEnd) {
        MarketDataEventsConsumer consumer = new MarketDataEventsConsumer(
                new ObjectMapper(),
                mock(PairExecutorRegistry.class),
                mock(MatchDispatcher.class),
                mock(FeedStatusRegistry.class));
        ReflectionTestUtils.setField(consumer, "seekToEndOnStart", seekToEnd);
        return consumer;
    }

    @Test
    void seeksAssignedPartitionsToEnd_whenEnabled() {
        MarketDataEventsConsumer consumer = newConsumer(true);
        ConsumerSeekCallback callback = mock(ConsumerSeekCallback.class);
        Map<TopicPartition, Long> assignments = Map.of(
                new TopicPartition("market-data.events.v1", 0), 0L,
                new TopicPartition("market-data.events.v1", 1), 0L);

        consumer.onPartitionsAssigned(assignments, callback);

        verify(callback).seekToEnd(assignments.keySet());
    }

    @Test
    void doesNotSeek_whenDisabled() {
        MarketDataEventsConsumer consumer = newConsumer(false);
        ConsumerSeekCallback callback = mock(ConsumerSeekCallback.class);
        Map<TopicPartition, Long> assignments = Map.of(
                new TopicPartition("market-data.events.v1", 0), 0L);

        consumer.onPartitionsAssigned(assignments, callback);

        verify(callback, never()).seekToEnd(Set.of(new TopicPartition("market-data.events.v1", 0)));
    }

    @Test
    void doesNotSeek_whenNoPartitionsAssigned() {
        MarketDataEventsConsumer consumer = newConsumer(true);
        ConsumerSeekCallback callback = mock(ConsumerSeekCallback.class);

        consumer.onPartitionsAssigned(Map.of(), callback);

        verify(callback, never()).seekToEnd(org.mockito.ArgumentMatchers.anyCollection());
    }
}
