package com.haizz.exchange.matching.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "matching")
public record AppProperties(
        JwtProperties jwt,
        KafkaTopicProperties kafka,
        OutboxProperties outbox,
        ClientsProperties clients,
        FeesProperties fees,
        MatchingProperties matching,
        FeedProperties feed
) {
    public record JwtProperties(
            String algorithm,
            String secret
    ) {}

    public record KafkaTopicProperties(
            String orderEventsTopic,
            String marketDataEventsTopic,
            String matchingEventsTopic,
            String tradeExecutedTopic
    ) {}

    public record OutboxProperties(long relayFixedDelayMs, int relayBatchSize) {}

    public record ClientsProperties(
            String marketdataBaseUrl,
            String orderBaseUrl
    ) {}

    public record FeesProperties(BigDecimal takerRate) {}

    public record MatchingProperties(BigDecimal marketSlippage) {}

    public record FeedProperties(int degradedAfterSeconds) {}
}
