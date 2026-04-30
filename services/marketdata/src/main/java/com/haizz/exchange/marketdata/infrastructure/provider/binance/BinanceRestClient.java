package com.haizz.exchange.marketdata.infrastructure.provider.binance;

import com.haizz.exchange.marketdata.config.BinanceProperties;
import com.haizz.exchange.marketdata.infrastructure.provider.binance.dto.BinanceExchangeInfo;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.ratelimiter.operator.RateLimiterOperator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.List;

@Slf4j
@Component
public class BinanceRestClient {

    private static final String RATE_LIMITER_NAME = "binanceRest";
    private static final String CIRCUIT_BREAKER_NAME = "binanceRest";

    private final WebClient webClient;
    private final RateLimiter rateLimiter;
    private final CircuitBreaker circuitBreaker;

    public BinanceRestClient(BinanceProperties props,
                             RateLimiterRegistry rateLimiterRegistry,
                             CircuitBreakerRegistry circuitBreakerRegistry) {
        this.webClient = WebClient.builder()
                .baseUrl(props.getRest().getBaseUrl())
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
        this.rateLimiter = rateLimiterRegistry.rateLimiter(RATE_LIMITER_NAME);
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER_NAME);
    }

    public Mono<BinanceExchangeInfo> getExchangeInfo() {
        return webClient.get()
                .uri("/api/v3/exchangeInfo")
                .retrieve()
                .bodyToMono(BinanceExchangeInfo.class)
                .transformDeferred(RateLimiterOperator.of(rateLimiter))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(1)).filter(this::isRetryable))
                .doOnError(e -> log.error("Failed to fetch exchangeInfo: {}", e.getMessage()));
    }

    @SuppressWarnings("unchecked")
    public Mono<List<List<Object>>> getKlines(String symbol, String interval,
                                              long startTimeMs, long endTimeMs, int limit) {
        return webClient.get()
                .uri(b -> b.path("/api/v3/klines")
                        .queryParam("symbol", symbol)
                        .queryParam("interval", interval)
                        .queryParam("startTime", startTimeMs)
                        .queryParam("endTime", endTimeMs)
                        .queryParam("limit", limit)
                        .build())
                .retrieve()
                .bodyToMono(List.class)
                .map(list -> (List<List<Object>>) list)
                .transformDeferred(RateLimiterOperator.of(rateLimiter))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(2)).filter(this::isRetryable));
    }

    private boolean isRetryable(Throwable t) {
        return !(t instanceof io.github.resilience4j.circuitbreaker.CallNotPermittedException);
    }
}
