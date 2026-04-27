package com.haizz.exchange.wallet.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "wallet")
public record AppProperties(
        JwtProperties jwt,
        KafkaTopicProperties kafka,
        OutboxProperties outbox,
        AssetsProperties assets
) {
    public record JwtProperties(
            String algorithm,
            String secret
    ) {}

    public record KafkaTopicProperties(
            String userEventsTopic,
            String tradeExecutedTopic,
            String walletTransactionsTopic
    ) {}

    public record OutboxProperties(long relayFixedDelayMs, int relayBatchSize) {}

    public record AssetsProperties(List<String> supportedAssets) {}
}
