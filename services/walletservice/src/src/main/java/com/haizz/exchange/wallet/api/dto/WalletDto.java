package com.haizz.exchange.wallet.api.dto;

import com.haizz.exchange.wallet.domain.Wallet;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record WalletDto(
        UUID walletId,
        String assetCode,
        BigDecimal total,
        BigDecimal available,
        BigDecimal frozen,
        Instant updatedAt
) {
    public static WalletDto from(Wallet w) {
        return new WalletDto(
                w.getWalletId(),
                w.getAssetCode(),
                w.getTotalBalance(),
                w.getAvailableBalance(),
                w.getFrozenBalance(),
                w.getUpdatedAt());
    }
}
