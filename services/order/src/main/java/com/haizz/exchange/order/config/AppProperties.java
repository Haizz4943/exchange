package com.haizz.exchange.order.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "order")
public record AppProperties(
        JwtProperties jwt,
        KafkaTopicProperties kafka,
        OutboxProperties outbox,
        ClientProperties clients,
        FeeProperties fees,
        LimitProperties limits
) {
    public record JwtProperties(
            String algorithm,
            String secret
    ) {}

    public record KafkaTopicProperties(
            String orderEventsTopic,
            String matchingEventsTopic
    ) {}

    public record OutboxProperties(long relayFixedDelayMs, int relayBatchSize) {}

    public record ClientProperties(
            String walletBaseUrl,
            String marketdataBaseUrl
    ) {}

    public record FeeProperties(BigDecimal takerRate) {}

    public record LimitProperties(int maxOpenOrdersPerPair) {}
}
