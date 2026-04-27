package com.haizz.exchange.wallet.domain.exception;

public class InsufficientFrozenBalanceException extends WalletException {
    public InsufficientFrozenBalanceException() {
        super("INSUFFICIENT_FROZEN_BALANCE",
                "Insufficient frozen balance — this indicates an upstream bug.");
    }
}
