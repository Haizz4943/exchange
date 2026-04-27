package com.haizz.exchange.wallet.api.dto;

import com.haizz.exchange.wallet.domain.WalletTransaction;
import com.haizz.exchange.wallet.domain.WalletTxnType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record WalletTransactionDto(
        UUID txnId,
        String assetCode,
        WalletTxnType type,
        BigDecimal deltaAvailable,
        BigDecimal deltaFrozen,
        BigDecimal deltaTotal,
        BigDecimal balanceAfterAvailable,
        BigDecimal balanceAfterFrozen,
        BigDecimal balanceAfterTotal,
        String referenceType,
        String referenceId,
        Instant createdAt
) {
    public static WalletTransactionDto from(WalletTransaction t) {
        return new WalletTransactionDto(
                t.getTxnId(),
                t.getAssetCode(),
                t.getType(),
                t.getDeltaAvailable(),
                t.getDeltaFrozen(),
                t.getDeltaTotal(),
                t.getBalanceAfterAvailable(),
                t.getBalanceAfterFrozen(),
                t.getBalanceAfterTotal(),
                t.getReferenceType(),
                t.getReferenceId(),
                t.getCreatedAt());
    }
}
