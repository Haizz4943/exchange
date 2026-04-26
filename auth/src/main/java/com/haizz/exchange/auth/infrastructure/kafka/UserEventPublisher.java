package com.haizz.exchange.auth.infrastructure.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haizz.exchange.auth.config.AppProperties;
import com.haizz.exchange.auth.domain.AuthOutbox;
import com.haizz.exchange.auth.domain.User;
import com.haizz.exchange.auth.infrastructure.persistence.AuthOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserEventPublisher {

    private final AuthOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    /**
     * Persists a UserRegistered event to the outbox within the same transaction as user creation.
     * The OutboxRelay will pick it up and publish to Kafka asynchronously.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueUserRegistered(User user) {
        UserRegisteredPayload payload = new UserRegisteredPayload(
                user.getId(),
                user.getEmailNormalized(),
                user.getCreatedAt(),
                user.getExternalProvider()
        );

        EventEnvelope<UserRegisteredPayload> envelope = EventEnvelope.of("UserRegistered", payload);

        try {
            String json = objectMapper.writeValueAsString(envelope);
            AuthOutbox outbox = AuthOutbox.of("UserRegistered", user.getId(), json);
            outboxRepository.save(outbox);
            log.info("Enqueued UserRegistered event for user={}", user.getId());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize UserRegistered event", e);
        }
    }

    public void publishToKafka(KafkaTemplate<String, String> kafkaTemplate,
                                String topic, AuthOutbox outbox) {
        kafkaTemplate.send(topic, outbox.getAggregateId().toString(), outbox.getPayloadJson())
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish outbox event id={} to topic={}", outbox.getId(), topic, ex);
                    }
                });
    }
}
