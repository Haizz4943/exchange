package com.haizz.exchange.gateway.config;

import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/**
 * Adds security response headers to all responses.
 */
@Configuration
public class SecurityHeadersConfig {

    @Bean
    public GlobalFilter securityHeadersFilter() {
        return (exchange, chain) -> chain.filter(exchange).then(Mono.fromRunnable(() -> {
            var headers = exchange.getResponse().getHeaders();
            headers.set("X-Content-Type-Options", "nosniff");
            headers.set("X-Frame-Options", "DENY");
            headers.set("X-XSS-Protection", "0");
            headers.set("Referrer-Policy", "strict-origin-when-cross-origin");
        }));
    }
}
