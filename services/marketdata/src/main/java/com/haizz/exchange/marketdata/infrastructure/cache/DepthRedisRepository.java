package com.haizz.exchange.marketdata.infrastructure.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haizz.exchange.marketdata.domain.DepthSnapshot;
import com.haizz.exchange.marketdata.domain.PairSymbol;
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
public class DepthRedisRepository {

    private static final Duration TTL = Duration.ofSeconds(5);

    private final ReactiveStringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public Mono<Void> set(PairSymbol pair, DepthSnapshot snapshot) {
        try {
            String json = objectMapper.writeValueAsString(snapshot);
            return redis.opsForValue()
                    .set(Constants.REDIS_DEPTH_PREFIX + pair.value(), json, TTL)
                    .then();
        } catch (Exception e) {
            log.error("Failed to serialize depth snapshot for {}: {}", pair, e.getMessage());
            return Mono.empty();
        }
    }

    public Mono<Optional<DepthSnapshot>> get(PairSymbol pair) {
        return redis.opsForValue()
                .get(Constants.REDIS_DEPTH_PREFIX + pair.value())
                .map(json -> {
                    try {
                        return Optional.of(objectMapper.readValue(json, DepthSnapshot.class));
                    } catch (Exception e) {
                        log.warn("Failed to deserialize depth for {}: {}", pair, e.getMessage());
                        return Optional.<DepthSnapshot>empty();
                    }
                })
                .defaultIfEmpty(Optional.empty());
    }
}
