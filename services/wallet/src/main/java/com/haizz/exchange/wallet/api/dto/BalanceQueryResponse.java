package com.haizz.exchange.wallet.api.dto;

import com.haizz.exchange.wallet.domain.Wallet;

import java.math.BigDecimal;

public record BalanceQueryResponse(
        String assetCode,
        BigDecimal total,
        BigDecimal available,
        BigDecimal frozen
) {
    public static BalanceQueryResponse from(Wallet w) {
        return new BalanceQueryResponse(
                w.getAssetCode(),
                w.getTotalBalance(),
                w.getAvailableBalance(),
                w.getFrozenBalance());
    }
}
