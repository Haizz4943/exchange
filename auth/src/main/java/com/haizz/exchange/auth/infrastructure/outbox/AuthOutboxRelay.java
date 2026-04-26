package com.haizz.exchange.auth.infrastructure.outbox;

import com.haizz.exchange.auth.config.AppProperties;
import com.haizz.exchange.auth.domain.AuthOutbox;
import com.haizz.exchange.auth.infrastructure.persistence.AuthOutboxRepository;
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
public class AuthOutboxRelay {

    private static final int MAX_ATTEMPTS = 10;

    private final AuthOutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final AppProperties appProperties;

    @Scheduled(fixedDelayString = "${auth.outbox.relay-fixed-delay-ms:100}")
    @Transactional
    public void relay() {
        int batchSize = appProperties.outbox().relayBatchSize();
        List<AuthOutbox> pending = outboxRepository.findPendingEvents(PageRequest.of(0, batchSize));

        for (AuthOutbox event : pending) {
            event.incrementAttempts();

            if (event.getAttempts() > MAX_ATTEMPTS) {
                log.error("Outbox event id={} exceeded max attempts — skipping", event.getId());
                continue;
            }

            try {
                String topic = resolveTopic(event.getEventType());
                kafkaTemplate.send(topic, event.getAggregateId().toString(), event.getPayloadJson())
                        .get(); // block to ensure delivery before marking published
                event.markPublished();
                log.info("Published outbox event type={} id={}", event.getEventType(), event.getId());
            } catch (Exception e) {
                log.error("Failed to publish outbox event id={} attempt={}", event.getId(), event.getAttempts(), e);
            }

            outboxRepository.save(event);
        }
    }

    private String resolveTopic(String eventType) {
        return switch (eventType) {
            case "UserRegistered" -> appProperties.kafka().userEventsTopic();
            default -> throw new IllegalArgumentException("Unknown event type: " + eventType);
        };
    }
}
