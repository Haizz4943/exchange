package com.haizz.exchange.marketdata.infrastructure.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haizz.exchange.marketdata.domain.DepthSnapshot;
import com.haizz.exchange.marketdata.domain.PairSymbol;
import com.haizz.exchange.marketdata.domain.Ticker;
import com.haizz.exchange.marketdata.shared.Constants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Optional;

@Slf4j
@Repository
@RequiredArgsConstructor
public class TickerRedisRepository {

    private static final Duration TTL = Duration.ofSeconds(10);

    private final ReactiveStringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public Mono<Void> setFromDepth(DepthSnapshot depth) {
        var ticker = Ticker.fromDepth(depth, depth.bids().isEmpty()
                ? java.math.BigDecimal.ZERO
                : depth.bids().get(0).get(0));
        return set(depth.pair(), ticker);
    }

    public Mono<Void> set(PairSymbol pair, Ticker ticker) {
        try {
            String json = objectMapper.writeValueAsString(ticker);
            return redis.opsForValue()
                    .set(Constants.REDIS_TICKER_PREFIX + pair.value(), json, TTL)
                    .then();
        } catch (Exception e) {
            log.error("Failed to serialize ticker for {}: {}", pair, e.getMessage());
            return Mono.empty();
        }
    }

    public Mono<Optional<Ticker>> get(PairSymbol pair) {
        return redis.opsForValue()
                .get(Constants.REDIS_TICKER_PREFIX + pair.value())
                .map(json -> {
                    try {
                        return Optional.of(objectMapper.readValue(json, Ticker.class));
                    } catch (Exception e) {
                        log.warn("Failed to deserialize ticker for {}: {}", pair, e.getMessage());
                        return Optional.<Ticker>empty();
                    }
                })
                .defaultIfEmpty(Optional.empty());
    }
}
