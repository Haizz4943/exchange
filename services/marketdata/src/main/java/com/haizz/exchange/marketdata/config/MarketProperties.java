package com.haizz.exchange.marketdata.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "market")
public class MarketProperties {

    private Data data = new Data();
    private List<String> pairs = List.of("BTCUSDT", "ETHUSDT", "BNBUSDT", "SOLUSDT", "XRPUSDT");
    private List<String> intervals = List.of("1m", "5m", "15m", "1h", "4h", "1d");
    private Backfill backfill = new Backfill();
    private Health health = new Health();
    private ExchangeInfo exchangeInfo = new ExchangeInfo();

    @lombok.Data
    public static class Data {
        private String provider = "binance";
    }

    @lombok.Data
    public static class Backfill {
        private Map<String, Integer> initialHistory = Map.of(
                "1m", 30, "5m", 60, "15m", 90, "1h", 90, "4h", 365, "1d", 365
        );
        private long gapCheckIntervalMs = 300_000;
    }

    @lombok.Data
    public static class Health {
        private long checkIntervalMs = 5000;
        private long staleThresholdMs = 2000;
        private long degradedThresholdMs = 10_000;
        private long degradedEventThresholdMs = 30_000;
    }

    @lombok.Data
    public static class ExchangeInfo {
        private long refreshIntervalMs = 86_400_000;
    }
}
