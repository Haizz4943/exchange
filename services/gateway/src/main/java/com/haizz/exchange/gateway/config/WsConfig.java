package com.haizz.exchange.gateway.config;

import com.haizz.exchange.gateway.ws.WsHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;

import java.util.Map;

/**
 * WebSocket endpoint configuration.
 *
 * Maps /ws → WsHandler (reactive WebFlux WebSocketHandler).
 * This is NOT a proxied route through Spring Cloud Gateway — it is handled directly
 * within the same Netty server because fan-out from Kafka is NOT a proxy use case.
 *
 * HandshakeInterceptor (servlet-style) cannot be used in WebFlux reactive mode.
 * Authentication is performed inside WsHandler.handle() by reading token from
 * session.getHandshakeInfo().getUri() query parameters.
 */
@Configuration
@RequiredArgsConstructor
public class WsConfig {

    private final WsHandler wsHandler;

    @Bean
    public HandlerMapping wsHandlerMapping() {
        Map<String, WebSocketHandler> map = Map.of("/ws", wsHandler);
        SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
        mapping.setUrlMap(map);
        // Order must be high priority so it runs before Spring Cloud Gateway route matching
        mapping.setOrder(-1);
        return mapping;
    }

    @Bean
    public WebSocketHandlerAdapter handlerAdapter() {
        return new WebSocketHandlerAdapter();
    }
}
