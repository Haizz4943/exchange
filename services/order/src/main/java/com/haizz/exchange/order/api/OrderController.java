package com.haizz.exchange.order.api;

import com.haizz.exchange.order.api.dto.OrderResponse;
import com.haizz.exchange.order.api.dto.PlaceOrderRequest;
import com.haizz.exchange.order.application.CancelOrderUseCase;
import com.haizz.exchange.order.application.PlaceOrderUseCase;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final PlaceOrderUseCase placeOrderUseCase;
    private final CancelOrderUseCase cancelOrderUseCase;

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
}
