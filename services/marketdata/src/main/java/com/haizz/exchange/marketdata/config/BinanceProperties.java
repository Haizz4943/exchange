package com.haizz.exchange.marketdata.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "binance")
public class BinanceProperties {

    private Rest rest = new Rest();
    private Ws ws = new Ws();
    private Kline kline = new Kline();

    @Data
    public static class Rest {
        private String baseUrl = "https://api.binance.com";
        private int connectTimeout = 2000;
        private int readTimeout = 10000;
    }

    @Data
    public static class Ws {
        private String baseUrl = "wss://stream.binance.com:9443";
        private Reconnect reconnect = new Reconnect();

        @Data
        public static class Reconnect {
            private long initialDelayMs = 1000;
            private long maxDelayMs = 60_000;
            private int multiplier = 2;
        }
    }

    @Data
    public static class Kline {
        private boolean useWs = false;
    }
}
