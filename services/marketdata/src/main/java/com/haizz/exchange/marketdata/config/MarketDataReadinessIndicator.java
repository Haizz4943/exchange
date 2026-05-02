package com.haizz.exchange.marketdata.config;

import com.haizz.exchange.marketdata.shared.StartupState;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.ReactiveHealthIndicator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component("marketDataReadiness")
@RequiredArgsConstructor
public class MarketDataReadinessIndicator implements ReactiveHealthIndicator {

    private final StartupState startupState;

    @Override
    public Mono<Health> health() {
        return Mono.fromSupplier(() -> {
            Health.Builder builder = startupState.isReady() ? Health.up() : Health.down();
            return builder
                    .withDetail("exchangeInfoLoaded", startupState.isExchangeInfoLoaded())
                    .withDetail("backfillComplete", startupState.isBackfillComplete())
                    .withDetail("wsConnected", startupState.isWsConnected())
                    .build();
        });
    }
}
