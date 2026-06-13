package com.haizz.exchange.gateway.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Metrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Routes Kafka messages to WebSocket connections.
 *
 * ON-WIRE SHAPES (per-topic, NOT uniform envelope — see DECISIONS.md §2):
 *
 * market-data.depth.v1   — raw {pair, bids, asks, updatedAt}
 * market-data.kline.v1   — raw {pair, interval, openTime(ISO), open,high,low,close,volume,closed,updatedAt}
 * market-data.events.v1  — EventEnvelope {eventId,eventType:"ExternalTradeObservedEvent",payload:{...}}
 * wallet.transactions.v1 — raw map {txnId,walletId,userId,assetCode,type,deltaAvailable,...}
 * matching.events.v1     — EventEnvelope {eventType, payload:{userId,...}} — DEFERRED
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WsMessageRouter {

    private final SubscriptionManager subscriptionManager;
    private final ConnectionRegistry registry;
    private final ObjectMapper objectMapper;

    /**
     * Route a Kafka message to subscribed WebSocket connections.
     *
     * @param topic   Kafka topic name
     * @param rawJson Raw JSON string from Kafka
     */
    public void route(String topic, String rawJson) {
        try {
            JsonNode raw = objectMapper.readTree(rawJson);
            Routing routing = resolveRouting(topic, raw);
            if (routing == null) {
                log.debug("No routing for topic={}", topic);
                return;
            }

            Set<String> subscribers = subscriptionManager.subscribersOf(routing.channel());
            if (subscribers.isEmpty()) return;

            // Serialize envelope once, reuse for all matching connections
            WsOutboundEnvelope envelope = new WsOutboundEnvelope(
                    routing.channel(), routing.schema(), routing.payload(), Instant.now());
            String serialized = objectMapper.writeValueAsString(envelope);

            int sent = 0;
            for (String connId : subscribers) {
                ConnectionRegistry.WsConnection conn = registry.get(connId);
                if (conn == null) continue;

                // User-scoped events: skip connections belonging to other users
                if (routing.userId() != null && !routing.userId().equals(conn.userId())) continue;

                conn.sink().tryEmitNext(serialized);
                sent++;
            }

            if (sent > 0) {
                Metrics.counter("gateway.ws.messages.sent", "channel", routing.channel()).increment(sent);
            }
        } catch (Exception e) {
            log.error("Failed to route topic={}: {}", topic, e.getMessage(), e);
        }
    }

    /**
     * Resolve routing per topic (NOT via a common envelope — each topic has a different wire shape).
     * Returns null for unknown topics.
     */
    Routing resolveRouting(String topic, JsonNode raw) {
        return switch (topic) {
            // --- broadcast depth updates ---
            case "market-data.depth.v1" -> {
                String pair = raw.path("pair").asText();
                if (pair.isBlank()) yield null;
                Map<String, Object> payload = new HashMap<>();
                payload.put("pair", pair);
                payload.put("bids", raw.get("bids"));
                payload.put("asks", raw.get("asks"));
                yield new Routing(
                        "market:" + pair + ":depth",
                        "market-data.depth.v1",
                        null,
                        payload
                );
            }

            // --- broadcast kline updates (openTime ISO → time epoch seconds) ---
            case "market-data.kline.v1" -> {
                String pair = raw.path("pair").asText();
                String interval = raw.path("interval").asText();
                if (pair.isBlank() || interval.isBlank()) yield null;

                // openTime is ISO-8601 string; FE expects time as epoch seconds (number)
                long timeEpochSeconds = parseOpenTime(raw.path("openTime").asText());

                Map<String, Object> payload = new HashMap<>();
                payload.put("pair", pair);
                payload.put("interval", interval);
                payload.put("time", timeEpochSeconds);
                payload.put("open", raw.path("open").asText());
                payload.put("high", raw.path("high").asText());
                payload.put("low", raw.path("low").asText());
                payload.put("close", raw.path("close").asText());
                payload.put("volume", raw.path("volume").asText());
                payload.put("closed", raw.path("closed").asBoolean());

                yield new Routing(
                        "market:" + pair + ":kline:" + interval,
                        "market-data.kline.v1",
                        null,
                        payload
                );
            }

            // --- broadcast external trade (wrapped in EventEnvelope) ---
            case "market-data.events.v1" -> {
                // Wire: {eventId, eventType:"ExternalTradeObservedEvent", ..., payload:{...}}
                JsonNode p = raw.path("payload");
                String pair = p.path("pair").asText();
                if (pair.isBlank()) yield null;

                // side: buyerIsMaker=true → the buyer was market maker → aggressive side is SELL
                boolean buyerIsMaker = p.path("buyerIsMaker").asBoolean(false);

                Map<String, Object> payload = new HashMap<>();
                payload.put("pair", pair);
                payload.put("price", p.path("price").asText());
                payload.put("quantity", p.path("quantity").asText());
                payload.put("side", buyerIsMaker ? "SELL" : "BUY");
                // eventTime → executedAt (FE TradesTape uses executedAt)
                payload.put("executedAt", p.path("eventTime").asText());

                yield new Routing(
                        "market:" + pair + ":trades",
                        "market-data.events.v1.ExternalTradeObserved",   // no "Event" suffix — FE contract
                        null,
                        payload
                );
            }

            // --- user-scoped wallet transaction (raw map, NOT wallet.events.v1) ---
            case "wallet.transactions.v1" -> {
                String userId = raw.path("userId").asText();
                if (userId.isBlank()) yield null;

                // Forward raw map as-is (only deltas available — see DECISIONS.md §6)
                yield new Routing(
                        "wallet",
                        "wallet.events.v1.WalletTransactionRecorded",
                        userId,
                        raw   // forward JsonNode as payload
                );
            }

            // --- user-scoped matching events (deferred — matching service not yet built) ---
            case "matching.events.v1" -> {
                JsonNode p = raw.path("payload");
                String userId = p.path("userId").asText();
                String eventType = raw.path("eventType").asText();
                if (userId.isBlank() || eventType.isBlank()) yield null;

                yield new Routing(
                        "orders",
                        "matching.events.v1." + eventType,
                        userId,
                        p
                );
            }

            default -> null;
        };
    }

    private long parseOpenTime(String openTime) {
        if (openTime == null || openTime.isBlank()) return 0L;
        try {
            return Instant.parse(openTime).getEpochSecond();
        } catch (Exception e) {
            // Try parsing as epoch millis fallback
            try {
                return Long.parseLong(openTime) / 1000;
            } catch (Exception ex) {
                log.warn("Cannot parse openTime: {}", openTime);
                return 0L;
            }
        }
    }

    /**
     * Routing result: where to send and what to send.
     */
    record Routing(
            String channel,
            String schema,
            String userId,   // null = broadcast; non-null = only deliver to this user
            Object payload
    ) {}

    /**
     * Outbound envelope written to the WebSocket.
     */
    record WsOutboundEnvelope(
            String channel,
            String schema,
            Object payload,
            Instant timestamp
    ) {}
}
