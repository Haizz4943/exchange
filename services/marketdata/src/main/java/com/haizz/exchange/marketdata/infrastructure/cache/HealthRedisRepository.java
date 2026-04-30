package com.haizz.exchange.marketdata.infrastructure.cache;

import com.haizz.exchange.marketdata.domain.FeedStatus;
import com.haizz.exchange.marketdata.domain.PairSymbol;
import com.haizz.exchange.marketdata.shared.Constants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Repository
@RequiredArgsConstructor
public class HealthRedisRepository {

    private final ReactiveStringRedisTemplate redis;

    // In-memory timestamps for low-latency health checks
    private final ConcurrentHashMap<String, Instant> tradeLastUpdate = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> depthLastUpdate = new ConcurrentHashMap<>();

    public void recordTrade(PairSymbol pair, Instant at) {
        tradeLastUpdate.put(pair.value(), at);
        redis.opsForHash().put(Constants.REDIS_HEALTH_PREFIX + pair.value(),
                "tradeLastUpdate", at.toString()).subscribe();
    }

    public void recordDepth(PairSymbol pair, Instant at) {
        depthLastUpdate.put(pair.value(), at);
        redis.opsForHash().put(Constants.REDIS_HEALTH_PREFIX + pair.value(),
                "depthLastUpdate", at.toString()).subscribe();
    }

    public Instant getTradeLastUpdate(PairSymbol pair) {
        return tradeLastUpdate.get(pair.value());
    }

    public Instant getDepthLastUpdate(PairSymbol pair) {
        return depthLastUpdate.get(pair.value());
    }

    public Mono<Void> setWsStatus(String status) {
        return redis.opsForValue().set(Constants.REDIS_WS_STATUS_KEY, status).then();
    }

    public Mono<String> getWsStatus() {
        return redis.opsForValue().get(Constants.REDIS_WS_STATUS_KEY)
                .defaultIfEmpty(Constants.WS_STATUS_DISCONNECTED);
    }
}
