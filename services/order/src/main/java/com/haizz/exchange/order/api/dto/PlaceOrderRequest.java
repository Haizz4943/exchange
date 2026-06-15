package com.haizz.exchange.order.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

/**
 * Place-order request body (JSON snake_case). HTTP-level validation only:
 * presence/blankness. Enum parsing, decimal parsing and business rules are
 * handled by {@code PlaceOrderUseCase} so they can emit API_SPEC error codes.
 */
public record PlaceOrderRequest(
        @JsonProperty("client_order_id") UUID clientOrderId,
        @JsonProperty("pair") @NotBlank String pair,
        @JsonProperty("side") @NotBlank String side,
        @JsonProperty("type") @NotBlank String type,
        @JsonProperty("quantity") @NotBlank String quantity,
        @JsonProperty("limit_price") String limitPrice,
        @JsonProperty("time_in_force") String timeInForce
) {}
