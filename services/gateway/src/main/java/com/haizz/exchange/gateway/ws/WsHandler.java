package com.haizz.exchange.gateway.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haizz.exchange.gateway.config.GatewayProperties;
import com.haizz.exchange.gateway.jwt.JwtClaims;
import com.haizz.exchange.gateway.jwt.JwtException;
import com.haizz.exchange.gateway.jwt.JwtVerifier;
import com.haizz.exchange.gateway.ws.dto.WsInboundMessage;
import com.haizz.exchange.gateway.ws.dto.WsOutboundMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Reactive WebSocket handler for the /ws endpoint.
 *
 * Handshake: token extracted from query param ?token=... or Authorization header.
 * JWT invalid → close 4401.
 * Max connections per user exceeded → close 4429.
 *
 * Inbound: {"op":"subscribe"|"unsubscribe","channels":[...]}
 * Outbound: {"channel","schema","payload","timestamp"}
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WsHandler implements WebSocketHandler {

    private static final CloseStatus AUTH_FAILED = CloseStatus.create(4401, "TOKEN_EXPIRED");
    private static final CloseStatus TOO_MANY_CONNECTIONS = CloseStatus.create(4429, "TOO_MANY_CONNECTIONS");

    private final JwtVerifier jwtVerifier;
    private final ConnectionRegistry registry;
    private final SubscriptionManager subscriptionManager;
    private final GatewayProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        // Step 1: authenticate via token in query param
        JwtClaims claims;
        try {
            String token = extractToken(session.getHandshakeInfo().getUri());
            claims = jwtVerifier.verify(token);
        } catch (JwtException e) {
            log.debug("WS handshake rejected: {} sessionId={}", e.getMessage(), session.getId());
            return session.close(AUTH_FAILED);
        }

        // Step 2: enforce max connections per user
        int maxConns = properties.ws() != null ? properties.ws().maxConnectionsPerUser() : 5;
        if (registry.connectionCountForUser(claims.userId()) >= maxConns) {
            log.warn("WS max connections exceeded userId={}", claims.userId());
            return session.close(TOO_MANY_CONNECTIONS);
        }

        // Step 3: register connection with a sink for outbound messages
        String connectionId = UUID.randomUUID().toString();
        Sinks.Many<String> sink = Sinks.many().multicast().onBackpressureBuffer();

        ConnectionRegistry.WsConnection conn = new ConnectionRegistry.WsConnection(
                connectionId,
                claims.userId(),
                session,
                sink,
                claims.expiresAt(),
                Instant.now()
        );
        registry.register(conn);
        log.info("WS connected userId={} connId={}", claims.userId(), connectionId);

        // Outbound: push from sink to session
        Mono<Void> sendMono = session.send(
                sink.asFlux().map(session::textMessage)
        );

        // Inbound: receive and process subscribe/unsubscribe messages
        Mono<Void> receiveMono = session.receive()
                .flatMap(msg -> {
                    try {
                        WsInboundMessage inbound = objectMapper.readValue(msg.getPayloadAsText(), WsInboundMessage.class);
                        return handleInbound(connectionId, inbound, sink);
                    } catch (Exception e) {
                        log.debug("WS failed to parse inbound message connId={}: {}", connectionId, e.getMessage());
                        return sendError(sink, "Invalid message format");
                    }
                })
                .then();

        // Cleanup on connection close
        return Mono.zip(sendMono, receiveMono)
                .doFinally(signal -> {
                    subscriptionManager.unsubscribeAll(connectionId);
                    registry.remove(connectionId);
                    sink.tryEmitComplete();
                    log.info("WS disconnected userId={} connId={} signal={}", claims.userId(), connectionId, signal);
                })
                .then();
    }

    private Mono<Void> handleInbound(String connectionId, WsInboundMessage msg, Sinks.Many<String> sink) {
        if (msg.channels() == null || msg.channels().isEmpty()) {
            return Mono.empty();
        }

        return switch (msg.op() != null ? msg.op() : "") {
            case "subscribe" -> {
                List<String> valid = new ArrayList<>();
                List<String> invalid = new ArrayList<>();
                for (String ch : msg.channels()) {
                    if (subscriptionManager.isValidChannel(ch)) {
                        valid.add(ch);
                    } else {
                        invalid.add(ch);
                    }
                }
                if (!valid.isEmpty()) {
                    subscriptionManager.subscribe(connectionId, valid);
                    // Send ack
                    sendAck(sink, "subscribed", valid);
                }
                if (!invalid.isEmpty()) {
                    log.debug("WS invalid channels connId={} channels={}", connectionId, invalid);
                    sendError(sink, "Invalid channel: " + invalid);
                }
                yield Mono.empty();
            }
            case "unsubscribe" -> {
                subscriptionManager.unsubscribe(connectionId, msg.channels());
                sendAck(sink, "unsubscribed", msg.channels());
                yield Mono.empty();
            }
            default -> sendError(sink, "Unknown op: " + msg.op());
        };
    }

    private void sendAck(Sinks.Many<String> sink, String op, List<String> channels) {
        try {
            String json = objectMapper.writeValueAsString(
                    java.util.Map.of("op", op, "channels", channels)
            );
            sink.tryEmitNext(json);
        } catch (Exception e) {
            log.error("Failed to serialize ack", e);
        }
    }

    private Mono<Void> sendError(Sinks.Many<String> sink, String message) {
        try {
            String json = objectMapper.writeValueAsString(
                    java.util.Map.of("op", "error", "message", message)
            );
            sink.tryEmitNext(json);
        } catch (Exception e) {
            log.error("Failed to serialize error frame", e);
        }
        return Mono.empty();
    }

    private String extractToken(URI uri) {
        if (uri == null) throw new JwtException("MISSING_TOKEN", "No URI");
        String query = uri.getQuery();
        if (query == null) throw new JwtException("MISSING_TOKEN", "No query string");
        for (String param : query.split("&")) {
            if (param.startsWith("token=")) {
                return java.net.URLDecoder.decode(param.substring(6), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        throw new JwtException("MISSING_TOKEN", "No token in query string");
    }

    /**
     * Scheduled eviction of connections with expired tokens.
     * Runs every 30 seconds.
     */
    @Scheduled(fixedDelayString = "${gateway.ws.token-expiry-check-interval-ms:30000}")
    public void evictExpiredConnections() {
        Instant now = Instant.now();
        registry.all().stream()
                .filter(conn -> conn.tokenExp() != null && conn.tokenExp().isBefore(now))
                .forEach(conn -> {
                    log.info("WS evicting expired token connId={} userId={}", conn.connectionId(), conn.userId());
                    conn.session().close(AUTH_FAILED).subscribe();
                    subscriptionManager.unsubscribeAll(conn.connectionId());
                    registry.remove(conn.connectionId());
                });
    }
}
