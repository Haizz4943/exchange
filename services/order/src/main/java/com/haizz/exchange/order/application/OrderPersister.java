package com.haizz.exchange.order.application;

import com.haizz.exchange.common.event.order.OrderPlacedEvent;
import com.haizz.exchange.order.domain.Order;
import com.haizz.exchange.order.infrastructure.outbox.OrderOutboxPublisher;
import com.haizz.exchange.order.infrastructure.persistence.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Persists a newly-placed order and enqueues its {@code OrderPlaced} outbox
 * event in ONE transaction (SR-036/040). Kept as a separate Spring bean so the
 * {@code @Transactional} proxy applies when invoked from {@link PlaceOrderUseCase}
 * (self-invocation would bypass the proxy).
 */
@Component
@RequiredArgsConstructor
public class OrderPersister {

    private final OrderRepository orderRepository;
    private final OrderOutboxPublisher outboxPublisher;

    @Transactional
    public Order persist(Order order) {
        Order saved = orderRepository.save(order);

        OrderPlacedEvent event = new OrderPlacedEvent(
                saved.getId(),
                saved.getUserId(),
                saved.getPair(),
                saved.getSide().name(),
                saved.getType().name(),
                saved.getQuantity(),
                saved.getLimitPrice(),
                Instant.now());

        outboxPublisher.enqueue("OrderPlaced", saved.getId(), event);
        return saved;
    }
}
