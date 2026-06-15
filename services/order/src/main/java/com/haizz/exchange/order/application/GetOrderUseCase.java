package com.haizz.exchange.order.application;

import com.haizz.exchange.common.web.ForbiddenException;
import com.haizz.exchange.order.api.dto.OrderResponse;
import com.haizz.exchange.order.domain.Order;
import com.haizz.exchange.order.domain.exception.OrderNotFoundException;
import com.haizz.exchange.order.infrastructure.persistence.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Get-one-order use case (SR-041). Loads by id; a missing order is a 404
 * ({@link OrderNotFoundException}); an order owned by another user is a 403
 * ({@link ForbiddenException}) — existence is not masked as 404 (see DECISIONS).
 */
@Service
@RequiredArgsConstructor
public class GetOrderUseCase {

    private final OrderRepository orderRepository;

    @Transactional(readOnly = true)
    public OrderResponse execute(UUID userId, UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId.toString()));
        if (!order.getUserId().equals(userId)) {
            throw new ForbiddenException("FORBIDDEN", "Order not owned by caller: " + orderId);
        }
        return OrderResponse.from(order);
    }
}
