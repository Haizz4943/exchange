package com.haizz.exchange.marketdata.infrastructure.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haizz.exchange.marketdata.domain.PairMetadata;
import com.haizz.exchange.marketdata.domain.PairSymbol;
import com.haizz.exchange.marketdata.shared.Constants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Repository
@RequiredArgsConstructor
public class ExchangeInfoRedisRepository {

    private static final Duration TTL = Duration.ofHours(25);

    private final ReactiveStringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public Mono<Void> putAll(Map<PairSymbol, PairMetadata> metadataMap) {
        return Mono.fromRunnable(() -> {
            metadataMap.forEach((pair, meta) -> {
                try {
                    String json = objectMapper.writeValueAsString(meta);
                    redis.opsForHash().put(Constants.REDIS_EXCHANGE_INFO_KEY, pair.value(), json)
                            .subscribe();
                } catch (Exception e) {
                    log.error("Failed to cache exchangeInfo for {}: {}", pair, e.getMessage());
                }
            });
        });
    }

    public Mono<Optional<PairMetadata>> get(PairSymbol pair) {
        return redis.opsForHash()
                .get(Constants.REDIS_EXCHANGE_INFO_KEY, pair.value())
                .map(json -> {
                    try {
                        return Optional.of(objectMapper.readValue(json.toString(), PairMetadata.class));
                    } catch (Exception e) {
                        log.warn("Failed to deserialize exchangeInfo for {}: {}", pair, e.getMessage());
                        return Optional.<PairMetadata>empty();
                    }
                })
                .defaultIfEmpty(Optional.empty());
    }

    public Mono<Map<PairSymbol, PairMetadata>> getAll() {
        return redis.opsForHash()
                .entries(Constants.REDIS_EXCHANGE_INFO_KEY)
                .collectMap(
                        entry -> PairSymbol.of(entry.getKey().toString()),
                        entry -> {
                            try {
                                return objectMapper.readValue(entry.getValue().toString(), PairMetadata.class);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        }
                );
    }
}
