package com.haizz.exchange.order.application;

import com.haizz.exchange.order.api.dto.InternalOrderProjection;
import com.haizz.exchange.order.api.dto.PageResponse;
import com.haizz.exchange.order.domain.Order;
import com.haizz.exchange.order.domain.OrderState;
import com.haizz.exchange.order.infrastructure.persistence.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Internal open-orders use case (API_SPEC §3.7) feeding the Matching Engine's
 * startup index rebuild. Returns ALL users' orders in the requested states (default
 * {@code OPEN, PARTIALLY_FILLED}) ordered by {@code createdAt ASC} (FIFO — matters
 * for matching priority), as a paged {@link InternalOrderProjection}. No user filter;
 * the endpoint is network-trust (no JWT).
 */
@Service
@RequiredArgsConstructor
public class ListOpenOrdersUseCase {

    static final List<OrderState> DEFAULT_STATES =
            List.of(OrderState.OPEN, OrderState.PARTIALLY_FILLED);
    static final int DEFAULT_SIZE = 1000;
    static final int MAX_SIZE = 1000;

    private final OrderRepository orderRepository;

    @Transactional(readOnly = true)
    public PageResponse<InternalOrderProjection> execute(String stateCsv, int page, int size) {
        List<OrderState> states = ListOrdersUseCase.parseStates(stateCsv);
        if (states.isEmpty()) {
            states = DEFAULT_STATES;
        }
        Pageable pageable = PageRequest.of(Math.max(page, 0), clampSize(size));
        Page<Order> result = orderRepository.findByStateInOrderByCreatedAtAsc(states, pageable);
        return PageResponse.of(result, InternalOrderProjection::from);
    }

    static int clampSize(int size) {
        if (size <= 0) {
            return DEFAULT_SIZE;
        }
        return Math.min(size, MAX_SIZE);
    }
}
