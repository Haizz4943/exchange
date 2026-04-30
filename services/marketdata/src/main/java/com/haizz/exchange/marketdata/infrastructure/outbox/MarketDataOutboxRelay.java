package com.haizz.exchange.marketdata.infrastructure.outbox;

import com.haizz.exchange.common.outbox.OutboxEntry;
import com.haizz.exchange.common.outbox.OutboxRelay;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class MarketDataOutboxRelay extends OutboxRelay {

    private final MarketDataOutboxJdbcRepository repository;
    private final KafkaTemplate<String, String> durableKafkaTemplate;

    public MarketDataOutboxRelay(MarketDataOutboxJdbcRepository repository,
                                 KafkaTemplate<String, String> durableKafkaTemplate) {
        this.repository = repository;
        this.durableKafkaTemplate = durableKafkaTemplate;
    }

    @Override
    protected List<OutboxEntry> fetchUnpublished(int batchSize) {
        return repository.fetchUnpublished(batchSize).stream()
                .map(e -> new OutboxEntry(e.id(), e.eventType(), e.topic(), e.partitionKey(),
                        e.payload(), e.attempts(), e.createdAt(), e.publishedAt(), e.lastError()))
                .toList();
    }

    @Override
    protected void markPublished(UUID id) {
        repository.markPublished(id);
    }

    @Override
    protected void incrementAttempts(UUID id, String error) {
        repository.incrementAttempts(id, error);
    }

    @Override
    protected void moveToDeadLetter(UUID id) {
        repository.moveToDeadLetter(id);
    }

    @Override
    protected KafkaTemplate<String, String> kafkaTemplate() {
        return durableKafkaTemplate;
    }
}
