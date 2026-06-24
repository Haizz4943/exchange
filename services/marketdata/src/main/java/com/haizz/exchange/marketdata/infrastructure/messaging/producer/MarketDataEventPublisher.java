package com.haizz.exchange.marketdata.infrastructure.messaging.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haizz.exchange.common.event.EventEnvelope;
import com.haizz.exchange.common.event.market.DepthUpdatedEvent;
import com.haizz.exchange.common.event.market.ExternalTradeObservedEvent;
import com.haizz.exchange.common.event.market.KlineUpdatedEvent;
import com.haizz.exchange.common.kafka.TopicNames;
import com.haizz.exchange.marketdata.domain.DepthSnapshot;
import com.haizz.exchange.marketdata.domain.KlineUpdate;
import com.haizz.exchange.marketdata.domain.TradeObservation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class MarketDataEventPublisher {

    private final KafkaTemplate<String, String> ephemeralKafkaTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Publishes an observed external trade directly (best-effort, ephemeral) to
     * {@code market-data.events.v1}. Like depth/kline, trades are high-rate ephemeral
     * market data — losing a few during a Kafka blip is acceptable, so they bypass the
     * durable outbox (which otherwise becomes an unbounded firehose backlog). The payload
     * stays wrapped in {@link EventEnvelope} so the matching consumer contract is unchanged.
     */
    public void publishExternalTrade(TradeObservation obs) {
        try {
            var event = new ExternalTradeObservedEvent(
                    obs.pair().value(),
                    obs.price(),
                    obs.quantity(),
                    obs.buyerIsMaker(),
                    obs.externalTradeId(),
                    obs.observedAt()
            );
            var envelope = EventEnvelope.of(
                    UUID.randomUUID().toString(), "ExternalTradeObservedEvent",
                    "market-data-service", null, event);
            ephemeralKafkaTemplate.send(
                    TopicNames.MARKET_DATA_EVENTS,
                    obs.pair().value(),
                    objectMapper.writeValueAsString(envelope));
        } catch (Exception e) {
            log.warn("Failed to publish external trade for {}: {}", obs.pair(), e.getMessage());
        }
    }

    public void publishDepthUpdate(DepthSnapshot snapshot) {
        try {
            var event = new DepthUpdatedEvent(
                    snapshot.pair().value(),
                    snapshot.bids(),
                    snapshot.asks(),
                    snapshot.updatedAt()
            );
            ephemeralKafkaTemplate.send(
                    TopicNames.MARKET_DATA_DEPTH,
                    snapshot.pair().value(),
                    objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            log.warn("Failed to publish depth update for {}: {}", snapshot.pair(), e.getMessage());
        }
    }

    public void publishKlineUpdate(KlineUpdate kline) {
        try {
            var event = new KlineUpdatedEvent(
                    kline.pair().value(),
                    kline.interval().getValue(),
                    kline.openTime(),
                    kline.open(), kline.high(), kline.low(), kline.close(),
                    kline.volume(), kline.closed(),
                    java.time.Instant.now()
            );
            ephemeralKafkaTemplate.send(
                    TopicNames.MARKET_DATA_KLINE,
                    kline.pair().value(),
                    objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            log.warn("Failed to publish kline update for {}: {}", kline.pair(), e.getMessage());
        }
    }
}
