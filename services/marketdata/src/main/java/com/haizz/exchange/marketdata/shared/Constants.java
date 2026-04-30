package com.haizz.exchange.marketdata.shared;

public final class Constants {

    private Constants() {}

    public static final int MAX_BARS_PER_REQUEST = 1000;
    public static final int BINANCE_MAX_BARS_PER_CALL = 1000;
    public static final int DEFAULT_DEPTH_LEVELS = 20;
    public static final int MAX_DEPTH_LEVELS = 20;

    public static final String REDIS_DEPTH_PREFIX = "md:depth:";
    public static final String REDIS_TICKER_PREFIX = "md:ticker:";
    public static final String REDIS_EXCHANGE_INFO_KEY = "md:exchangeInfo";
    public static final String REDIS_HEALTH_PREFIX = "md:health:";
    public static final String REDIS_KLINE_LATEST_PREFIX = "md:kline:";
    public static final String REDIS_WS_STATUS_KEY = "md:binance:ws:status";

    public static final String WS_STATUS_CONNECTED = "CONNECTED";
    public static final String WS_STATUS_DISCONNECTED = "DISCONNECTED";

    public static final double PRICE_SANITY_THRESHOLD = 0.10;
    public static final int SUSPICIOUS_TICK_ALERT_THRESHOLD = 5;
}
