package com.haizz.exchange.matching.infrastructure.outbox;

import com.haizz.exchange.matching.config.AppProperties;
import com.haizz.exchange.matching.domain.MatchingOutbox;
import com.haizz.exchange.matching.infrastructure.persistence.MatchingOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class MatchingOutboxRelay {

    private static final int MAX_ATTEMPTS = 10;

    private final MatchingOutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final AppProperties appProperties;

    @Scheduled(fixedDelayString = "${matching.outbox.relay-fixed-delay-ms:100}")
    @Transactional
    public void relay() {
        int batchSize = appProperties.outbox().relayBatchSize();
        List<MatchingOutbox> pending = outboxRepository.findPendingEvents(
                PageRequest.of(0, batchSize));

        for (MatchingOutbox event : pending) {
            event.incrementAttempts();

            if (event.getAttempts() > MAX_ATTEMPTS) {
                log.error("Outbox event id={} exceeded max attempts — skipping", event.getId());
                continue;
            }

            try {
                // Topic is resolved from the row, not the eventType: matching
                // publishes to two topics (trade.executed + matching.events.v1).
                kafkaTemplate.send(event.getTopic(), event.getPartitionKey(), event.getPayloadJson())
                        .get();
                event.markPublished();
                log.info("Published outbox event type={} topic={} id={}",
                        event.getEventType(), event.getTopic(), event.getId());
            } catch (Exception e) {
                event.setLastError(e.getMessage());
                log.error("Failed to publish outbox event id={} attempt={}",
                        event.getId(), event.getAttempts(), e);
            }

            outboxRepository.save(event);
        }
    }
}
