package com.haizz.exchange.common.event.wallet;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record WalletTransactionEvent(
        UUID transactionId,
        UUID userId,
        String asset,
        String type,
        BigDecimal amount,
        BigDecimal balanceAfter,
        Instant occurredAt
) {}
