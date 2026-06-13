package com.haizz.exchange.gateway.config;

import com.haizz.exchange.gateway.filter.CorrelationIdFilter;
import com.haizz.exchange.gateway.filter.InternalPathBlockFilter;
import com.haizz.exchange.gateway.filter.JwtAuthenticationFilter;
import com.haizz.exchange.gateway.filter.RateLimitFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Programmatic route definitions for all HTTP proxy routes.
 * /ws is NOT a proxied route — it is handled by WsHandler directly via HandlerMapping.
 *
 * Filter chain order per route: CorrelationIdFilter(5) → JwtAuthenticationFilter(10) → RateLimitFilter(20)
 */
@Configuration
@RequiredArgsConstructor
public class RouteConfig {

    private final CorrelationIdFilter correlationIdFilter;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RateLimitFilter rateLimitFilter;
    private final InternalPathBlockFilter internalPathBlockFilter;
    private final GatewayProperties properties;

    @Bean
    public RouteLocator customRouteLocator(RouteLocatorBuilder builder) {
        GatewayProperties.RoutesProperties routes = properties.routes();
        String authUri = routes != null ? routes.authUri() : "http://localhost:8081";
        String walletUri = routes != null ? routes.walletUri() : "http://localhost:8082";
        String orderUri = routes != null ? routes.orderUri() : "http://localhost:8083";
        String tradeUri = routes != null ? routes.tradeUri() : "http://localhost:8084";
        String marketdataUri = routes != null ? routes.marketdataUri() : "http://localhost:8085";

        return builder.routes()

                // Block /internal/** — defense in depth
                .route("block-internal", r -> r
                        .path("/internal/**")
                        .filters(f -> f.filter(internalPathBlockFilter))
                        .uri("no://op"))

                // Auth — all paths: no JWT (auth service handles its own security)
                // Note: auth service's /me and /logout are authenticated, but auth handles that internally;
                // we don't double-verify here to avoid circular dependency.
                .route("auth", r -> r
                        .path("/api/v1/auth/**")
                        .filters(f -> f
                                .filter(correlationIdFilter)
                                .filter(rateLimitFilter))
                        .uri(authUri))

                // Wallet service — JWT required
                .route("wallet", r -> r
                        .path("/api/v1/wallets/**", "/api/v1/deposits/**",
                                "/api/v1/withdrawals/**", "/api/v1/wallet-transactions/**")
                        .filters(f -> f
                                .filter(correlationIdFilter)
                                .filter(jwtAuthenticationFilter)
                                .filter(rateLimitFilter))
                        .uri(walletUri))

                // Orders — JWT required
                .route("orders", r -> r
                        .path("/api/v1/orders/**")
                        .filters(f -> f
                                .filter(correlationIdFilter)
                                .filter(jwtAuthenticationFilter)
                                .filter(rateLimitFilter))
                        .uri(orderUri))

                // Trading pairs and assets — public (no JWT)
                .route("trading-pairs-assets", r -> r
                        .path("/api/v1/trading-pairs/**", "/api/v1/assets/**")
                        .filters(f -> f
                                .filter(correlationIdFilter)
                                .filter(rateLimitFilter))
                        .uri(orderUri))

                // Trades (matching engine) — JWT required
                .route("trades", r -> r
                        .path("/api/v1/trades/**")
                        .filters(f -> f
                                .filter(correlationIdFilter)
                                .filter(jwtAuthenticationFilter)
                                .filter(rateLimitFilter))
                        .uri(tradeUri))

                // UDF (TradingView data feed) — public, rate-limited
                .route("udf", r -> r
                        .path("/udf/**")
                        .filters(f -> f
                                .filter(correlationIdFilter)
                                .filter(rateLimitFilter))
                        .uri(marketdataUri))

                // Market data REST — JWT required
                .route("marketdata", r -> r
                        .path("/api/v1/marketdata/**")
                        .filters(f -> f
                                .filter(correlationIdFilter)
                                .filter(jwtAuthenticationFilter)
                                .filter(rateLimitFilter))
                        .uri(marketdataUri))

                .build();
    }
}
