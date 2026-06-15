package com.haizz.exchange.order.api;

import com.haizz.exchange.order.api.dto.OrderResponse;
import com.haizz.exchange.order.api.dto.PageResponse;
import com.haizz.exchange.order.api.dto.PlaceOrderRequest;
import com.haizz.exchange.order.application.CancelOrderUseCase;
import com.haizz.exchange.order.application.GetOrderUseCase;
import com.haizz.exchange.order.application.ListOrdersUseCase;
import com.haizz.exchange.order.application.ListOrdersUseCase.OrderListQuery;
import com.haizz.exchange.order.application.PlaceOrderUseCase;
import com.haizz.exchange.order.domain.exception.InvalidOrderException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    /** Whitelist of sortable API field names -> entity properties (snake_case -> camelCase). */
    private static final Map<String, String> SORT_FIELDS = Map.of(
            "created_at", "createdAt",
            "updated_at", "updatedAt");

    private final PlaceOrderUseCase placeOrderUseCase;
    private final CancelOrderUseCase cancelOrderUseCase;
    private final GetOrderUseCase getOrderUseCase;
    private final ListOrdersUseCase listOrdersUseCase;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse placeOrder(@AuthenticationPrincipal Jwt jwt,
                                    @Valid @RequestBody PlaceOrderRequest request) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return placeOrderUseCase.execute(userId, request);
    }

    @DeleteMapping("/{orderId}")
    @ResponseStatus(HttpStatus.OK)
    public OrderResponse cancelOrder(@AuthenticationPrincipal Jwt jwt,
                                     @PathVariable UUID orderId) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return cancelOrderUseCase.execute(userId, orderId);
    }

    @GetMapping("/{orderId}")
    @ResponseStatus(HttpStatus.OK)
    public OrderResponse getOrder(@AuthenticationPrincipal Jwt jwt,
                                  @PathVariable UUID orderId) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return getOrderUseCase.execute(userId, orderId);
    }

    @GetMapping
    @ResponseStatus(HttpStatus.OK)
    public PageResponse<OrderResponse> listOrders(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String pair,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(defaultValue = "created_at,desc") String sort) {
        UUID userId = UUID.fromString(jwt.getSubject());
        OrderListQuery query = new OrderListQuery(
                pair, state, parseInstant(from, "from"), parseInstant(to, "to"),
                page, size, parseSort(sort));
        return listOrdersUseCase.execute(userId, query);
    }

    /**
     * Parses {@code created_at,desc} style sort into a Spring {@link Sort}. The field is
     * whitelisted (snake_case -> entity property); an unknown field/direction is a 400.
     */
    private Sort parseSort(String sort) {
        String[] parts = sort.split(",");
        String field = parts[0].trim();
        String property = SORT_FIELDS.get(field);
        if (property == null) {
            throw new InvalidOrderException("INVALID_SORT", "Unsupported sort field: " + field);
        }
        Sort.Direction direction = Sort.Direction.DESC;
        if (parts.length > 1 && !parts[1].isBlank()) {
            direction = Sort.Direction.fromOptionalString(parts[1].trim())
                    .orElseThrow(() -> new InvalidOrderException(
                            "INVALID_SORT", "Unsupported sort direction: " + parts[1].trim()));
        }
        return Sort.by(direction, property);
    }

    /**
     * Parses an ISO date ({@code 2026-04-01}, taken as start-of-day UTC) or full ISO
     * instant ({@code 2026-04-01T10:00:00Z}). Blank/null -> no bound. Bad input -> 400.
     */
    private Instant parseInstant(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        try {
            return Instant.parse(v);
        } catch (DateTimeParseException ignored) {
            // fall through to date-only parsing
        }
        try {
            return LocalDate.parse(v).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException e) {
            throw new InvalidOrderException("INVALID_DATE",
                    "Invalid " + field + " date/instant: " + value);
        }
    }
}
