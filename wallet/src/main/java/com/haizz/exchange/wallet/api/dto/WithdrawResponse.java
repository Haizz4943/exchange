package com.haizz.exchange.wallet.api.dto;

import com.haizz.exchange.wallet.domain.DepositStatus;
import com.haizz.exchange.wallet.domain.Wallet;
import com.haizz.exchange.wallet.domain.WithdrawalRecord;

import java.math.BigDecimal;
import java.util.UUID;

public record WithdrawResponse(
        UUID withdrawalId,
        DepositStatus status,
        BigDecimal amount,
        String assetCode,
        WalletDto wallet
) {
    public static WithdrawResponse from(WithdrawalRecord record, Wallet wallet) {
        return new WithdrawResponse(
                record.getWithdrawalId(),
                record.getStatus(),
                record.getAmount(),
                record.getAssetCode(),
                WalletDto.from(wallet));
    }
}
