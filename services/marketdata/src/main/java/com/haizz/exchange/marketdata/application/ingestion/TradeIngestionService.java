package com.haizz.exchange.marketdata.application.ingestion;

import com.haizz.exchange.common.event.market.ExternalTradeObservedEvent;
import com.haizz.exchange.common.kafka.TopicNames;
import com.haizz.exchange.marketdata.domain.TradeObservation;
import com.haizz.exchange.marketdata.infrastructure.cache.HealthRedisRepository;
import com.haizz.exchange.marketdata.infrastructure.outbox.MarketDataOutbox;
import com.haizz.exchange.marketdata.infrastructure.provider.MarketDataProvider;
import com.haizz.exchange.marketdata.shared.Constants;
import com.haizz.exchange.marketdata.shared.SupportedPairs;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class TradeIngestionService {

    private final MarketDataProvider provider;
    private final MarketDataOutbox outbox;
    private final HealthRedisRepository health;
    private final SupportedPairs supportedPairs;

    // Track last valid price per pair for sanity checking (SR-MD-MD-010)
    private final ConcurrentHashMap<String, java.math.BigDecimal> lastValidPrice = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> suspiciousTickCount = new ConcurrentHashMap<>();

    @PostConstruct
    public void start() {
        log.info("Starting trade ingestion for pairs: {}", supportedPairs.getPairValues());
        provider.streamTrades(supportedPairs.getPairs())
                .onBackpressureBuffer(10_000)
                .flatMap(this::processTradeEvent, 4)
                .doOnError(err -> log.error("Trade ingestion pipeline error", err))
                .retry()
                .subscribe();
    }

    private Mono<Void> processTradeEvent(TradeObservation obs) {
        return Mono.fromRunnable(() -> {
            checkPriceSanity(obs);
            health.recordTrade(obs.pair(), obs.observedAt());
            var event = new ExternalTradeObservedEvent(
                    obs.pair().value(),
                    obs.price(),
                    obs.quantity(),
                    obs.buyerIsMaker(),
                    obs.externalTradeId(),
                    obs.observedAt()
            );
            outbox.write("ExternalTradeObservedEvent", TopicNames.MARKET_DATA_EVENTS,
                    obs.pair().value(), event);
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    private void checkPriceSanity(TradeObservation obs) {
        String pair = obs.pair().value();
        var last = lastValidPrice.get(pair);
        if (last != null && last.compareTo(java.math.BigDecimal.ZERO) > 0) {
            double change = Math.abs(obs.price().subtract(last)
                    .divide(last, 10, java.math.RoundingMode.HALF_UP)
                    .doubleValue());
            if (change > Constants.PRICE_SANITY_THRESHOLD) {
                int count = suspiciousTickCount.merge(pair, 1, Integer::sum);
                log.warn("Suspicious price tick for {}: last={} new={} change={}% (count={})",
                        pair, last, obs.price(), String.format("%.2f", change * 100), count);
            } else {
                suspiciousTickCount.put(pair, 0);
            }
        }
        lastValidPrice.put(pair, obs.price());
    }
}
