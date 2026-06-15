package com.haizz.exchange.order.application;

import com.haizz.exchange.common.event.order.OrderCancelledEvent;
import com.haizz.exchange.common.web.ForbiddenException;
import com.haizz.exchange.order.api.dto.OrderResponse;
import com.haizz.exchange.order.application.CancelOrderUseCase.CancelResult;
import com.haizz.exchange.order.domain.Order;
import com.haizz.exchange.order.domain.exception.OrderNotCancellableException;
import com.haizz.exchange.order.domain.exception.OrderNotFoundException;
import com.haizz.exchange.order.infrastructure.outbox.OrderOutboxPublisher;
import com.haizz.exchange.order.infrastructure.persistence.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Validates ownership/cancellability and persists CANCEL_REQUESTED + the
 * {@code OrderCancelled} outbox event in ONE transaction (SR-038/039/040). Kept
 * as a separate Spring bean so the {@code @Transactional} proxy applies when
 * invoked from {@link CancelOrderUseCase} (self-invocation would bypass it).
 */
@Component
@RequiredArgsConstructor
public class CancelOrderPersister {

    private final OrderRepository orderRepository;
    private final OrderOutboxPublisher outboxPublisher;

    @Transactional
    public CancelResult cancel(UUID userId, UUID orderId) {
        // 1. Load with a pessimistic write-lock for the duration of the tx.
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId.toString()));

        // 2. Ownership (SR-039): a user may only cancel their own order.
        if (!order.getUserId().equals(userId)) {
            throw new ForbiddenException("FORBIDDEN",
                    "Order " + orderId + " does not belong to the caller");
        }

        // 3. Cancellability (SR-038): only NEW / OPEN / PARTIALLY_FILLED.
        if (!order.getState().isCancellable()) {
            throw new OrderNotCancellableException(orderId.toString(), order.getState().name());
        }

        // 4. Compute the frozen amount to release (unfilled portion).
        BigDecimal releaseAmount = CancelOrderUseCase.computeReleaseAmount(order);

        // 5. Transition to CANCEL_REQUESTED (terminal CANCELLED arrives later
        //    from the matching engine).
        order.markCancelRequested();
        Order saved = orderRepository.save(order);

        // 6. Enqueue the OrderCancelled outbox event (SR-040).
        OrderCancelledEvent event = new OrderCancelledEvent(
                saved.getId(),
                saved.getUserId(),
                saved.getPair(),
                CancelOrderUseCase.CANCELLED_REASON,
                Instant.now());
        outboxPublisher.enqueue("OrderCancelled", saved.getId(), event);

        return new CancelResult(saved.getFreezeAsset(), releaseAmount,
                OrderResponse.from(saved));
    }
}
