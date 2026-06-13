package com.haizz.exchange.gateway.ws;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Sinks;

import java.time.Instant;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory registry of active WebSocket connections.
 * Thread-safe via ConcurrentHashMap.
 *
 * Redis keys (ws:conn:*) are written as observability aids but NOT read back for routing —
 * in-memory is the authoritative state. On restart connections drop and clients reconnect.
 */
@Slf4j
@Component
public class ConnectionRegistry {

    private final ConcurrentHashMap<String, WsConnection> connections = new ConcurrentHashMap<>();
    // userId → Set<connectionId>: one user may have multiple browser tabs
    private final ConcurrentHashMap<String, Set<String>> userConnections = new ConcurrentHashMap<>();

    public record WsConnection(
            String connectionId,
            String userId,
            WebSocketSession session,
            Sinks.Many<String> sink,  // text messages pushed to this session
            Instant tokenExp,
            Instant createdAt
    ) {}

    public void register(WsConnection conn) {
        connections.put(conn.connectionId(), conn);
        userConnections.computeIfAbsent(conn.userId(), k -> ConcurrentHashMap.newKeySet())
                .add(conn.connectionId());
        log.debug("WS registered connId={} userId={}", conn.connectionId(), conn.userId());
    }

    public void remove(String connectionId) {
        WsConnection conn = connections.remove(connectionId);
        if (conn != null) {
            Set<String> userSet = userConnections.get(conn.userId());
            if (userSet != null) {
                userSet.remove(connectionId);
                if (userSet.isEmpty()) {
                    userConnections.remove(conn.userId());
                }
            }
            log.debug("WS removed connId={} userId={}", connectionId, conn.userId());
        }
    }

    public WsConnection get(String connectionId) {
        return connections.get(connectionId);
    }

    public Set<String> connectionIdsForUser(String userId) {
        return userConnections.getOrDefault(userId, Set.of());
    }

    public int connectionCountForUser(String userId) {
        return connectionIdsForUser(userId).size();
    }

    public int activeCount() {
        return connections.size();
    }

    public Collection<WsConnection> all() {
        return connections.values();
    }
}
