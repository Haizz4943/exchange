package com.haizz.exchange.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(
        JwtProperties jwt,
        RateLimitProperties rateLimit,
        WsProperties ws,
        RoutesProperties routes
) {
    public record JwtProperties(
            String algorithm,
            String secret,
            String publicKey,
            String issuer
    ) {
        public String algorithm() { return algorithm != null ? algorithm : "HS256"; }
        public String secret() { return secret != null ? secret : "change-me-in-prod-must-be-at-least-32-chars!"; }
        public String issuer() { return issuer != null ? issuer : "haizz-auth"; }
    }

    public record RateLimitProperties(
            TierProperties user,
            TierProperties ip
    ) {
        public record TierProperties(int capacity, double refillRate) {}
    }

    public record WsProperties(
            int maxConnectionsPerUser,
            int maxSubscriptionsPerConnection,
            long tokenExpiryCheckIntervalMs
    ) {
        public int maxConnectionsPerUser() { return maxConnectionsPerUser > 0 ? maxConnectionsPerUser : 5; }
        public int maxSubscriptionsPerConnection() { return maxSubscriptionsPerConnection > 0 ? maxSubscriptionsPerConnection : 20; }
        public long tokenExpiryCheckIntervalMs() { return tokenExpiryCheckIntervalMs > 0 ? tokenExpiryCheckIntervalMs : 30_000; }
    }

    public record RoutesProperties(
            String authUri,
            String walletUri,
            String orderUri,
            String tradeUri,
            String marketdataUri
    ) {
        public String authUri() { return authUri != null ? authUri : "http://localhost:8081"; }
        public String walletUri() { return walletUri != null ? walletUri : "http://localhost:8082"; }
        public String orderUri() { return orderUri != null ? orderUri : "http://localhost:8083"; }
        public String tradeUri() { return tradeUri != null ? tradeUri : "http://localhost:8084"; }
        public String marketdataUri() { return marketdataUri != null ? marketdataUri : "http://localhost:8085"; }
    }
}
