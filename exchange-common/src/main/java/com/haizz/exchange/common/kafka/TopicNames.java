package com.haizz.exchange.common.kafka;

public final class TopicNames {

    private TopicNames() {}

    public static final String USER_EVENTS       = "user.events.v1";
    public static final String ORDER_EVENTS      = "orders.events.v1";
    public static final String MATCHING_EVENTS   = "matching.events.v1";
    public static final String WALLET_EVENTS     = "wallet.events.v1";
    public static final String MARKET_DATA_EVENTS = "market-data.events.v1";
    public static final String MARKET_DATA_DEPTH  = "market-data.depth.v1";
    public static final String MARKET_DATA_KLINE  = "market-data.kline.v1";
}
