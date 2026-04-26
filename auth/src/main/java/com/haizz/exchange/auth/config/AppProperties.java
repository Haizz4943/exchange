package com.haizz.exchange.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "auth")
public record AppProperties(
        JwtProperties jwt,
        RateLimitProperties rateLimit,
        SsoProperties sso,
        KafkaTopicProperties kafka,
        OutboxProperties outbox
) {
    public record JwtProperties(
            String algorithm,
            String secret,
            String privateKeyPath,
            String publicKeyPath,
            long accessTokenTtlSeconds,
            long refreshTokenTtlSeconds,
            String issuer
    ) {}

    public record RateLimitProperties(
            int maxFailAttempts,
            long failWindowSeconds,
            long lockoutSeconds,
            int maxIpAttempts,
            long ipWindowSeconds
    ) {}

    public record SsoProperties(boolean enabled) {}

    public record KafkaTopicProperties(String userEventsTopic) {}

    public record OutboxProperties(long relayFixedDelayMs, int relayBatchSize) {}
}
