package com.haizz.exchange.common.kafka;

public final class KafkaHeaders {

    private KafkaHeaders() {}

    public static final String EVENT_TYPE      = "eventType";
    public static final String EVENT_ID        = "eventId";
    public static final String CORRELATION_ID  = "correlationId";
    public static final String SOURCE          = "source";
    public static final String VERSION         = "version";
}
