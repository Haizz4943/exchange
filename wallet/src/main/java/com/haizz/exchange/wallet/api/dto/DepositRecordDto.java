package com.haizz.exchange.wallet.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record DepositRecordDto(
        String referenceId,
        BigDecimal amount,
        String assetCode,
        Instant createdAt
) {}
