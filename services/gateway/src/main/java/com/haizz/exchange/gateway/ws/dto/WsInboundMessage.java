package com.haizz.exchange.gateway.ws.dto;

import java.util.List;

/**
 * Inbound WebSocket message from client.
 * Example: {"op":"subscribe","channels":["market:BTCUSDT:depth","orders","wallet"]}
 */
public record WsInboundMessage(
        String op,           // "subscribe" | "unsubscribe"
        List<String> channels
) {}
