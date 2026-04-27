package com.haizz.exchange.wallet.api.dto;

import com.haizz.exchange.wallet.domain.DepositRecord;
import com.haizz.exchange.wallet.domain.DepositStatus;
import com.haizz.exchange.wallet.domain.Wallet;

import java.math.BigDecimal;
import java.util.UUID;

public record DepositResponse(
        UUID depositId,
        DepositStatus status,
        BigDecimal amount,
        String assetCode,
        WalletDto wallet
) {
    public static DepositResponse from(DepositRecord record, Wallet wallet) {
        return new DepositResponse(
                record.getDepositId(),
                record.getStatus(),
                record.getAmount(),
                record.getAssetCode(),
                WalletDto.from(wallet));
    }
}
