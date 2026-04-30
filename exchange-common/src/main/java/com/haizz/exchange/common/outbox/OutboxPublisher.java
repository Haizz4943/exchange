package com.haizz.exchange.common.outbox;

public interface OutboxPublisher {
    void publish(String eventType, String topic, String partitionKey, Object payload);
}
