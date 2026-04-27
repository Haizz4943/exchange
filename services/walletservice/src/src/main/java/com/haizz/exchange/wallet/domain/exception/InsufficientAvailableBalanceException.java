package com.haizz.exchange.wallet.domain.exception;

import lombok.Getter;

import java.math.BigDecimal;

@Getter
public class InsufficientAvailableBalanceException extends WalletException {

    private final BigDecimal available;
    private final BigDecimal requested;
    private final BigDecimal frozen;

    public InsufficientAvailableBalanceException(BigDecimal available, BigDecimal requested, BigDecimal frozen) {
        super("INSUFFICIENT_AVAILABLE_BALANCE",
                "Insufficient available balance. Cancel open orders to free frozen balance.");
        this.available = available;
        this.requested = requested;
        this.frozen = frozen;
    }
}
