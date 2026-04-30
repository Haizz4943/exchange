package com.haizz.exchange.marketdata.infrastructure.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haizz.exchange.common.event.EventEnvelope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class MarketDataOutbox {

    private final MarketDataOutboxJdbcRepository repository;
    private final ObjectMapper objectMapper;

    public void write(String eventType, String topic, String partitionKey, Object payload) {
        try {
            var envelope = EventEnvelope.of(
                    UUID.randomUUID().toString(), eventType,
                    "market-data-service", null, payload);
            var json = objectMapper.writeValueAsString(envelope);
            var entry = new MarketDataOutboxEntry(
                    UUID.randomUUID(), eventType, topic, partitionKey, json,
                    0, java.time.Instant.now(), null, null);
            repository.save(entry);
        } catch (Exception e) {
            log.error("Failed to write to outbox eventType={}: {}", eventType, e.getMessage());
        }
    }
}
