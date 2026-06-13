package com.haizz.exchange.gateway.ws;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Kafka consumer for WebSocket fan-out.
 * Consumer group: ws-fanout
 * auto-offset-reset: latest (fan-out is ephemeral; clients reconnect and get fresh data)
 *
 * Topics consumed (per DECISIONS.md §2 — non-uniform wire shapes):
 *   market-data.depth.v1    — raw DepthUpdatedEvent
 *   market-data.kline.v1    — raw KlineUpdatedEvent
 *   market-data.events.v1   — EventEnvelope<ExternalTradeObservedEvent>
 *   wallet.transactions.v1  — raw WalletTransaction map (NOT wallet.events.v1)
 *   matching.events.v1      — EventEnvelope (DEFERRED — topic may not exist yet; listener is ready)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaFanoutConsumer {

    private final WsMessageRouter router;

    @KafkaListener(
            topics = "market-data.depth.v1",
            groupId = "ws-fanout",
            containerFactory = "wsFanoutKafkaListenerContainerFactory"
    )
    public void onDepth(@Payload String payload) {
        router.route("market-data.depth.v1", payload);
    }

    @KafkaListener(
            topics = "market-data.kline.v1",
            groupId = "ws-fanout",
            containerFactory = "wsFanoutKafkaListenerContainerFactory"
    )
    public void onKline(@Payload String payload) {
        router.route("market-data.kline.v1", payload);
    }

    @KafkaListener(
            topics = "market-data.events.v1",
            groupId = "ws-fanout",
            containerFactory = "wsFanoutKafkaListenerContainerFactory"
    )
    public void onMarketDataEvent(@Payload String payload) {
        router.route("market-data.events.v1", payload);
    }

    @KafkaListener(
            topics = "wallet.transactions.v1",
            groupId = "ws-fanout",
            containerFactory = "wsFanoutKafkaListenerContainerFactory"
    )
    public void onWalletTransaction(@Payload String payload) {
        router.route("wallet.transactions.v1", payload);
    }

    @KafkaListener(
            topics = "matching.events.v1",
            groupId = "ws-fanout",
            containerFactory = "wsFanoutKafkaListenerContainerFactory"
    )
    public void onMatchingEvent(@Payload String payload) {
        // DEFERRED: matching service not yet built; topic may not exist yet
        // listener is ready for when it does
        router.route("matching.events.v1", payload);
    }
}
