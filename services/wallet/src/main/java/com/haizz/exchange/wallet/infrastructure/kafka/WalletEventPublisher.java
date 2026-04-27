package com.haizz.exchange.wallet.infrastructure.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haizz.exchange.wallet.domain.WalletOutbox;
import com.haizz.exchange.wallet.domain.WalletTransaction;
import com.haizz.exchange.wallet.infrastructure.persistence.WalletOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class WalletEventPublisher {

    private final WalletOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    /** Enqueue a WalletTransaction event within the caller's DB transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueWalletTransaction(WalletTransaction txn) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("txnId", txn.getTxnId().toString());
        payload.put("walletId", txn.getWalletId().toString());
        payload.put("userId", txn.getUserId().toString());
        payload.put("assetCode", txn.getAssetCode());
        payload.put("type", txn.getType().name());
        payload.put("deltaAvailable", txn.getDeltaAvailable());
        payload.put("deltaFrozen", txn.getDeltaFrozen());
        payload.put("deltaTotal", txn.getDeltaTotal());
        payload.put("referenceType", txn.getReferenceType());
        payload.put("referenceId", txn.getReferenceId());
        payload.put("createdAt", txn.getCreatedAt().toString());

        try {
            String json = objectMapper.writeValueAsString(payload);
            WalletOutbox outbox = WalletOutbox.of("WalletTransaction", txn.getWalletId(), json);
            outboxRepository.save(outbox);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize WalletTransaction event", e);
        }
    }

    public void publishToKafka(KafkaTemplate<String, String> kafkaTemplate,
            String topic, WalletOutbox outbox) {
        kafkaTemplate.send(topic, outbox.getAggregateId().toString(), outbox.getPayloadJson())
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish outbox event id={} topic={}",
                                outbox.getId(), topic, ex);
                    }
                });
    }
}
