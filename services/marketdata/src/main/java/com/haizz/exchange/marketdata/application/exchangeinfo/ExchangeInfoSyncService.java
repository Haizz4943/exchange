package com.haizz.exchange.marketdata.application.exchangeinfo;

import com.haizz.exchange.common.event.market.PairMetadataUpdatedEvent;
import com.haizz.exchange.common.kafka.TopicNames;
import com.haizz.exchange.marketdata.domain.PairMetadata;
import com.haizz.exchange.marketdata.domain.PairSymbol;
import com.haizz.exchange.marketdata.infrastructure.cache.ExchangeInfoRedisRepository;
import com.haizz.exchange.marketdata.infrastructure.outbox.MarketDataOutbox;
import com.haizz.exchange.marketdata.infrastructure.provider.MarketDataProvider;
import com.haizz.exchange.marketdata.shared.StartupState;
import com.haizz.exchange.marketdata.shared.SupportedPairs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExchangeInfoSyncService {

    private final MarketDataProvider provider;
    private final ExchangeInfoRedisRepository cache;
    private final MarketDataOutbox outbox;
    private final StartupState startupState;
    private final SupportedPairs supportedPairs;

    public Mono<Void> runStartupSync() {
        return sync()
                .doOnSuccess(v -> {
                    log.info("ExchangeInfo loaded successfully");
                    startupState.markExchangeInfoLoaded();
                })
                .doOnError(e -> log.error("ExchangeInfo sync failed on startup: {}", e.getMessage()));
    }

    @Scheduled(fixedRateString = "${market.exchange-info.refresh-interval-ms:86400000}",
               initialDelay = 86_400_000)
    public void scheduledRefresh() {
        sync().subscribe(null, e -> log.error("Scheduled ExchangeInfo sync failed: {}", e.getMessage()));
    }

    private Mono<Void> sync() {
        return provider.fetchExchangeInfo()
                .flatMap(newInfo -> {
                    // Filter to only supported pairs
                    Map<PairSymbol, PairMetadata> filtered = new java.util.HashMap<>();
                    newInfo.forEach((pair, meta) -> {
                        if (supportedPairs.supports(pair)) filtered.put(pair, meta);
                    });

                    return cache.getAll()
                            .flatMap(oldInfo -> {
                                emitChangedFields(oldInfo, filtered);
                                return cache.putAll(filtered);
                            });
                });
    }

    private void emitChangedFields(Map<PairSymbol, PairMetadata> oldInfo,
                                   Map<PairSymbol, PairMetadata> newInfo) {
        newInfo.forEach((pair, newMeta) -> {
            PairMetadata oldMeta = oldInfo.get(pair);
            if (oldMeta == null) return; // new pair — don't emit on first run
            if (!oldMeta.tickSize().equals(newMeta.tickSize())) {
                outbox.write("PairMetadataUpdatedEvent", TopicNames.MARKET_DATA_EVENTS, pair.value(),
                        new PairMetadataUpdatedEvent(pair.value(), "tickSize",
                                oldMeta.tickSize(), newMeta.tickSize(), Instant.now()));
            }
            if (!oldMeta.stepSize().equals(newMeta.stepSize())) {
                outbox.write("PairMetadataUpdatedEvent", TopicNames.MARKET_DATA_EVENTS, pair.value(),
                        new PairMetadataUpdatedEvent(pair.value(), "stepSize",
                                oldMeta.stepSize(), newMeta.stepSize(), Instant.now()));
            }
        });
    }
}
