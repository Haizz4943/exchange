package com.haizz.exchange.matching.domain;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks the market-data feed health per pair.
 *
 * <p>The matching engine should only execute against a pair whose external feed is
 * trustworthy. A DEGRADED/DISCONNECTED feed means we cannot trust the external price,
 * so {@link #isTradeable(String)} returns {@code false} and phase-3 matching should skip.
 *
 * <p>Thread-safe ({@link ConcurrentHashMap}); may be read/written from multiple consumer
 * threads as well as per-pair executor threads.
 */
@Slf4j
@Component
public class FeedStatusRegistry {

    public enum FeedStatus {
        HEALTHY,
        STALE,
        DEGRADED,
        DISCONNECTED
    }

    /** Immutable snapshot of a pair's feed status and when it last changed. */
    public record FeedState(FeedStatus status, Instant lastUpdate) {}

    private final Map<String, FeedState> byPair = new ConcurrentHashMap<>();

    public void markDegraded(String pair) {
        update(pair, FeedStatus.DEGRADED);
    }

    public void markRecovered(String pair) {
        update(pair, FeedStatus.HEALTHY);
    }

    public void update(String pair, FeedStatus status) {
        byPair.put(pair, new FeedState(status, Instant.now()));
        log.info("Feed status for pair={} -> {}", pair, status);
    }

    /** Current status for a pair; defaults to HEALTHY when unknown. */
    public FeedStatus statusOf(String pair) {
        FeedState state = byPair.get(pair);
        return state == null ? FeedStatus.HEALTHY : state.status();
    }

    /** True unless the feed is DEGRADED or DISCONNECTED. Unknown pairs default tradeable. */
    public boolean isTradeable(String pair) {
        FeedStatus status = statusOf(pair);
        return status != FeedStatus.DEGRADED && status != FeedStatus.DISCONNECTED;
    }
}
