package com.haizz.exchange.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haizz.exchange.common.web.ErrorResponse;
import com.haizz.exchange.gateway.config.GatewayProperties;
import com.haizz.exchange.gateway.ratelimit.RedisRateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Per-user (120 burst / 60 s) and per-IP (240 burst / 120 s) rate limiting using Redis token bucket.
 * Order 20 — runs after JWT filter (order 10).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitFilter implements GatewayFilter, Ordered {

    private final RedisRateLimiter rateLimiter;
    private final GatewayProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String userId = exchange.getRequest().getHeaders().getFirst("X-User-Id");
        String ip = extractClientIp(exchange);

        GatewayProperties.RateLimitProperties rl = properties.rateLimit();
        int ipCapacity = rl != null && rl.ip() != null ? rl.ip().capacity() : 240;
        double ipRefill = rl != null && rl.ip() != null ? rl.ip().refillRate() : 120.0;
        int userCapacity = rl != null && rl.user() != null ? rl.user().capacity() : 120;
        double userRefill = rl != null && rl.user() != null ? rl.user().refillRate() : 60.0;

        Mono<Boolean> ipCheck = rateLimiter.isAllowed("gw:rl:ip:" + ip, ipCapacity, ipRefill);
        Mono<Boolean> userCheck = userId != null
                ? rateLimiter.isAllowed("gw:rl:user:" + userId, userCapacity, userRefill)
                : Mono.just(true);

        return Mono.zip(ipCheck, userCheck)
                .flatMap(tuple -> {
                    boolean ipAllowed = tuple.getT1();
                    boolean userAllowed = tuple.getT2();
                    if (!ipAllowed || !userAllowed) {
                        return writeRateLimitResponse(exchange);
                    }
                    return chain.filter(exchange);
                });
    }

    private String extractClientIp(ServerWebExchange exchange) {
        String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        var addr = exchange.getRequest().getRemoteAddress();
        return addr != null ? addr.getAddress().getHostAddress() : "unknown";
    }

    private Mono<Void> writeRateLimitResponse(ServerWebExchange exchange) {
        String correlationId = exchange.getRequest().getHeaders().getFirst(CorrelationIdFilter.HEADER);
        ErrorResponse error = ErrorResponse.of("RATE_LIMIT_EXCEEDED", "Too many requests", correlationId);

        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().set("Retry-After", "1");
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

        try {
            byte[] bytes = objectMapper.writeValueAsBytes(error);
            DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
            return exchange.getResponse().writeWith(Mono.just(buffer));
        } catch (Exception e) {
            log.error("Failed to serialize rate limit response", e);
            return exchange.getResponse().setComplete();
        }
    }

    @Override
    public int getOrder() {
        return 20;
    }
}
