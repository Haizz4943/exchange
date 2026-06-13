package com.haizz.exchange.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haizz.exchange.common.web.ErrorResponse;
import com.haizz.exchange.gateway.jwt.JwtClaims;
import com.haizz.exchange.gateway.jwt.JwtException;
import com.haizz.exchange.gateway.jwt.JwtVerifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Verifies JWT and injects X-User-* headers downstream.
 * Order 10 — runs after CorrelationIdFilter (order 5).
 *
 * Public paths bypass JWT verification (match prefix):
 *   /api/v1/auth/
 *   /udf/
 *   /api/v1/trading-pairs/
 *   /api/v1/assets/
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter implements GatewayFilter, Ordered {

    private static final List<String> PUBLIC_PATH_PREFIXES = List.of(
            "/api/v1/auth/",
            "/udf/",
            "/api/v1/trading-pairs/",
            "/api/v1/assets/"
    );

    private final JwtVerifier jwtVerifier;
    private final ObjectMapper objectMapper;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        if (isPublicPath(path)) {
            return chain.filter(exchange);
        }

        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return writeErrorResponse(exchange, HttpStatus.UNAUTHORIZED, "MISSING_TOKEN", "Authentication token is required");
        }

        try {
            String token = authHeader.substring(7);
            JwtClaims claims = jwtVerifier.verify(token);

            ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                    .header("X-User-Id", claims.userId())
                    .header("X-User-Email", claims.email() != null ? claims.email() : "")
                    .header("X-User-Roles", claims.scope() != null ? claims.scope() : "")
                    .header("X-User-Scope", claims.scope() != null ? claims.scope() : "")
                    .build();

            return chain.filter(exchange.mutate().request(mutatedRequest).build());
        } catch (JwtException e) {
            return writeErrorResponse(exchange, HttpStatus.UNAUTHORIZED, e.getCode(), e.getMessage());
        }
    }

    private boolean isPublicPath(String path) {
        return PUBLIC_PATH_PREFIXES.stream().anyMatch(path::startsWith);
    }

    private Mono<Void> writeErrorResponse(ServerWebExchange exchange, HttpStatus status,
                                           String code, String message) {
        String correlationId = exchange.getRequest().getHeaders().getFirst(CorrelationIdFilter.HEADER);
        ErrorResponse error = ErrorResponse.of(code, message, correlationId);

        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

        try {
            byte[] bytes = objectMapper.writeValueAsBytes(error);
            DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
            return exchange.getResponse().writeWith(Mono.just(buffer));
        } catch (Exception e) {
            log.error("Failed to serialize error response", e);
            return exchange.getResponse().setComplete();
        }
    }

    @Override
    public int getOrder() {
        return 10;
    }
}
