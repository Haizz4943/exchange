package com.haizz.exchange.gateway.ws.dto;

import java.time.Instant;

/**
 * Outbound WebSocket message pushed to client.
 * The FE WsClient dispatches by the `schema` field.
 *
 * Example:
 * {"channel":"market:BTCUSDT:depth","schema":"market-data.depth.v1","payload":{...},"timestamp":"..."}
 */
public record WsOutboundMessage(
        String channel,
        String schema,
        Object payload,
        Instant timestamp
) {
    public static WsOutboundMessage of(String channel, String schema, Object payload) {
        return new WsOutboundMessage(channel, schema, payload, Instant.now());
    }

    /** Error frame sent back to client for invalid channel etc. */
    public static WsOutboundMessage error(String message) {
        return new WsOutboundMessage("system", "system.error",
                java.util.Map.of("op", "error", "message", message), Instant.now());
    }
}
