package com.haizz.exchange.gateway.health;

import com.haizz.exchange.gateway.ws.ConnectionRegistry;
import com.haizz.exchange.gateway.ws.SubscriptionManager;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.ReactiveHealthIndicator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Exposes WebSocket connection count and subscription count via /actuator/health.
 * Uses Spring Boot 4.x health package (org.springframework.boot.health.contributor).
 */
@Component("wsHealth")
@RequiredArgsConstructor
public class WsHealthIndicator implements ReactiveHealthIndicator {

    private final ConnectionRegistry registry;
    private final SubscriptionManager subscriptionManager;

    @Override
    public Mono<Health> health() {
        return Mono.just(Health.up()
                .withDetail("active_connections", registry.activeCount())
                .withDetail("active_subscriptions", subscriptionManager.totalCount())
                .build());
    }
}
