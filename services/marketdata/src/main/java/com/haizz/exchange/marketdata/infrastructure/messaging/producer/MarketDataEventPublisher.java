package com.haizz.exchange.marketdata.infrastructure.messaging.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haizz.exchange.common.event.market.DepthUpdatedEvent;
import com.haizz.exchange.common.event.market.KlineUpdatedEvent;
import com.haizz.exchange.common.kafka.TopicNames;
import com.haizz.exchange.marketdata.domain.DepthSnapshot;
import com.haizz.exchange.marketdata.domain.KlineUpdate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MarketDataEventPublisher {

    private final KafkaTemplate<String, String> ephemeralKafkaTemplate;
    private final ObjectMapper objectMapper;

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
