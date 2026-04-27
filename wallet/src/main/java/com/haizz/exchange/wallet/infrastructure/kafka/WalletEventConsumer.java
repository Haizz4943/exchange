package com.haizz.exchange.wallet.infrastructure.kafka;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haizz.exchange.wallet.application.InitializeWalletsUseCase;
import com.haizz.exchange.wallet.application.ProcessTradeEventUseCase;
import com.haizz.exchange.wallet.infrastructure.kafka.event.EventEnvelope;
import com.haizz.exchange.wallet.infrastructure.kafka.event.TradeExecutedEvent;
import com.haizz.exchange.wallet.infrastructure.kafka.event.UserRegisteredEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class WalletEventConsumer {

    private final InitializeWalletsUseCase initializeWalletsUseCase;
    private final ProcessTradeEventUseCase processTradeEventUseCase;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "${wallet.kafka.user-events-topic:user.events.v1}",
            groupId = "wallet-service",
            concurrency = "1"
    )
    public void onUserEvent(ConsumerRecord<String, String> record) {
        try {
            EventEnvelope<UserRegisteredEvent> envelope = objectMapper.readValue(
                    record.value(),
                    new TypeReference<EventEnvelope<UserRegisteredEvent>>() {});

            if ("UserRegistered".equals(envelope.eventType())) {
                log.info("Received UserRegistered event for userId={}",
                        envelope.payload().userId());
                initializeWalletsUseCase.execute(envelope.payload());
            } else {
                log.debug("Ignoring unknown user event type={}", envelope.eventType());
            }
        } catch (Exception e) {
            log.error("Failed to process user event key={} offset={}",
                    record.key(), record.offset(), e);
        }
    }

    @KafkaListener(
            topics = "${wallet.kafka.trade-executed-topic:trade.executed}",
            groupId = "wallet-service",
            concurrency = "3"
    )
    public void onTradeExecuted(ConsumerRecord<String, String> record) {
        try {
            EventEnvelope<TradeExecutedEvent> envelope = objectMapper.readValue(
                    record.value(),
                    new TypeReference<EventEnvelope<TradeExecutedEvent>>() {});

            log.info("Received TradeExecuted event tradeId={}",
                    envelope.payload().tradeId());
            processTradeEventUseCase.execute(envelope.payload());
        } catch (Exception e) {
            log.error("Failed to process trade event key={} offset={}",
                    record.key(), record.offset(), e);
        }
    }
}
