package com.haizz.exchange.order.application;

import com.haizz.exchange.order.api.dto.OrderResponse;
import com.haizz.exchange.order.api.dto.PageResponse;
import com.haizz.exchange.order.domain.Order;
import com.haizz.exchange.order.domain.OrderState;
import com.haizz.exchange.order.domain.exception.InvalidOrderException;
import com.haizz.exchange.order.infrastructure.persistence.OrderRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * List-orders use case (SR-041). Builds a JPA {@link Specification} filtered by the
 * caller's userId (always) plus the optional pair / state-in / createdAt-between
 * filters, applies sort + clamped paging, and maps the resulting {@link Page} into a
 * snake_case {@link PageResponse} of {@link OrderResponse}.
 * <p>
 * A Specification is used (over explicit query methods) so each optional filter is
 * additive and the combinatorial explosion of finder methods is avoided.
 */
@Service
@RequiredArgsConstructor
public class ListOrdersUseCase {

    /** Hard upper bound on page size (API_SPEC §3.4: max 500). */
    static final int MAX_SIZE = 500;
    static final int DEFAULT_SIZE = 50;

    private final OrderRepository orderRepository;

    @Transactional(readOnly = true)
    public PageResponse<OrderResponse> execute(UUID userId, OrderListQuery query) {
        Specification<Order> spec = buildSpec(userId, query);
        Pageable pageable = PageRequest.of(
                Math.max(query.page(), 0),
                clampSize(query.size()),
                query.sort());
        Page<Order> page = orderRepository.findAll(spec, pageable);
        return PageResponse.of(page, OrderResponse::from);
    }

    static int clampSize(int size) {
        if (size <= 0) {
            return DEFAULT_SIZE;
        }
        return Math.min(size, MAX_SIZE);
    }

    private Specification<Order> buildSpec(UUID userId, OrderListQuery query) {
        List<OrderState> states = parseStates(query.state());
        return (root, cq, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("userId"), userId));
            if (StringUtils.hasText(query.pair())) {
                predicates.add(cb.equal(root.get("pair"), query.pair()));
            }
            if (!states.isEmpty()) {
                predicates.add(root.get("state").in(states));
            }
            if (query.from() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), query.from()));
            }
            if (query.to() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), query.to()));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /**
     * Parses the comma-separated {@code state} CSV into {@link OrderState}s. Unknown
     * tokens are rejected with a 400 ({@code INVALID_STATE}) rather than silently
     * skipped, so a typo surfaces to the caller instead of returning a wrong result
     * set (see DECISIONS). Blank input means "all states" (empty list → no filter).
     */
    static List<OrderState> parseStates(String csv) {
        List<OrderState> states = new ArrayList<>();
        if (!StringUtils.hasText(csv)) {
            return states;
        }
        for (String token : csv.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                states.add(OrderState.valueOf(trimmed.toUpperCase()));
            } catch (IllegalArgumentException e) {
                throw new InvalidOrderException("INVALID_STATE", "Unknown order state: " + trimmed);
            }
        }
        return states;
    }

    /** Filter inputs for the list endpoint. {@code sort} is pre-resolved by the controller. */
    public record OrderListQuery(
            String pair,
            String state,
            Instant from,
            Instant to,
            int page,
            int size,
            Sort sort
    ) {
    }
}
