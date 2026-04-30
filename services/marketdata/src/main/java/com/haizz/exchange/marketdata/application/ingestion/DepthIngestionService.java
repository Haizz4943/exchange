package com.haizz.exchange.marketdata.application.ingestion;

import com.haizz.exchange.marketdata.domain.DepthSnapshot;
import com.haizz.exchange.marketdata.infrastructure.cache.DepthRedisRepository;
import com.haizz.exchange.marketdata.infrastructure.cache.HealthRedisRepository;
import com.haizz.exchange.marketdata.infrastructure.cache.TickerRedisRepository;
import com.haizz.exchange.marketdata.infrastructure.messaging.producer.MarketDataEventPublisher;
import com.haizz.exchange.marketdata.infrastructure.provider.MarketDataProvider;
import com.haizz.exchange.marketdata.shared.SupportedPairs;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class DepthIngestionService {

    private final MarketDataProvider provider;
    private final DepthRedisRepository depthCache;
    private final TickerRedisRepository tickerCache;
    private final HealthRedisRepository healthRepo;
    private final MarketDataEventPublisher eventPublisher;
    private final SupportedPairs supportedPairs;

    @PostConstruct
    public void start() {
        log.info("Starting depth ingestion for pairs: {}", supportedPairs.getPairValues());
        provider.streamDepth(supportedPairs.getPairs())
                .onBackpressureLatest()
                .flatMap(this::processDepthSnapshot, 4)
                .doOnError(err -> log.error("Depth ingestion pipeline error", err))
                .retry()
                .subscribe();
    }

    private Mono<Void> processDepthSnapshot(DepthSnapshot snap) {
        return depthCache.set(snap.pair(), snap)
                .then(tickerCache.setFromDepth(snap))
                .then(Mono.fromRunnable(() -> {
                    healthRepo.recordDepth(snap.pair(), Instant.now());
                    eventPublisher.publishDepthUpdate(snap);
                }))
                .then();
    }
}
