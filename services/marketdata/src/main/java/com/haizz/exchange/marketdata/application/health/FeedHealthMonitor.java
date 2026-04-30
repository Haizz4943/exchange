package com.haizz.exchange.marketdata.application.health;

import com.haizz.exchange.common.event.market.MarketDataFeedDegradedEvent;
import com.haizz.exchange.common.event.market.MarketDataFeedRecoveredEvent;
import com.haizz.exchange.common.kafka.TopicNames;
import com.haizz.exchange.marketdata.config.MarketProperties;
import com.haizz.exchange.marketdata.domain.FeedStatus;
import com.haizz.exchange.marketdata.domain.PairHealth;
import com.haizz.exchange.marketdata.domain.PairSymbol;
import com.haizz.exchange.marketdata.infrastructure.cache.HealthRedisRepository;
import com.haizz.exchange.marketdata.infrastructure.outbox.MarketDataOutbox;
import com.haizz.exchange.marketdata.shared.SupportedPairs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class FeedHealthMonitor {

    private final HealthRedisRepository health;
    private final MarketDataOutbox outbox;
    private final SupportedPairs supportedPairs;
    private final MarketProperties properties;

    private final ConcurrentHashMap<PairSymbol, FeedStatus> lastKnownStatus = new ConcurrentHashMap<>();

    @Scheduled(fixedDelayString = "${market.health.check-interval-ms:5000}", initialDelay = 30_000)
    public void check() {
        var now = Instant.now();
        for (var pair : supportedPairs.getPairs()) {
            FeedStatus current = computeStatus(pair, now);
            FeedStatus prev = lastKnownStatus.getOrDefault(pair, FeedStatus.HEALTHY);
            if (current != prev) {
                log.info("Feed status change for {}: {} → {}", pair, prev, current);
                emitTransitionEvent(pair, prev, current, now);
                lastKnownStatus.put(pair, current);
            }
        }
    }

    private FeedStatus computeStatus(PairSymbol pair, Instant now) {
        Instant tradeLast = health.getTradeLastUpdate(pair);
        Instant depthLast = health.getDepthLastUpdate(pair);

        // Use the most recent of trade or depth as heartbeat (SR-MD-MD-010 §10.4)
        Instant lastBeat = latest(tradeLast, depthLast);
        if (lastBeat == null) return FeedStatus.DISCONNECTED;

        long sinceMs = now.toEpochMilli() - lastBeat.toEpochMilli();
        long staleMs = properties.getHealth().getStaleThresholdMs();
        long degradedMs = properties.getHealth().getDegradedThresholdMs();
        long degradedEventMs = properties.getHealth().getDegradedEventThresholdMs();

        if (sinceMs <= staleMs) return FeedStatus.HEALTHY;
        if (sinceMs <= degradedMs) return FeedStatus.STALE;
        if (sinceMs <= degradedEventMs) return FeedStatus.DEGRADED;
        return FeedStatus.DISCONNECTED;
    }

    private void emitTransitionEvent(PairSymbol pair, FeedStatus from, FeedStatus to, Instant now) {
        if (to == FeedStatus.DEGRADED || to == FeedStatus.DISCONNECTED) {
            if (from == FeedStatus.HEALTHY || from == FeedStatus.STALE) {
                outbox.write("MarketDataFeedDegradedEvent", TopicNames.MARKET_DATA_EVENTS, pair.value(),
                        new MarketDataFeedDegradedEvent(pair.value(),
                                to == FeedStatus.DISCONNECTED ? "WS_DISCONNECTED" : "STALE_STREAM",
                                to.name(), now));
            }
        } else if (to == FeedStatus.HEALTHY) {
            if (from == FeedStatus.DEGRADED || from == FeedStatus.DISCONNECTED) {
                outbox.write("MarketDataFeedRecoveredEvent", TopicNames.MARKET_DATA_EVENTS, pair.value(),
                        new MarketDataFeedRecoveredEvent(pair.value(), now));
            }
        }
    }

    public Map<PairSymbol, PairHealth> getAllHealth() {
        var now = Instant.now();
        var result = new HashMap<PairSymbol, PairHealth>();
        for (var pair : supportedPairs.getPairs()) {
            result.put(pair, new PairHealth(
                    pair,
                    health.getTradeLastUpdate(pair),
                    health.getDepthLastUpdate(pair),
                    computeStatus(pair, now)
            ));
        }
        return result;
    }

    private Instant latest(Instant a, Instant b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isAfter(b) ? a : b;
    }
}
