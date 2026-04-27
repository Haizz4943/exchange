package com.haizz.exchange.wallet.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record UnfreezeRequest(
        @NotNull UUID userId,
        @NotBlank String assetCode,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal amount,
        @NotBlank String referenceType,
        @NotBlank String referenceId,
        @NotBlank String reason
) {}
