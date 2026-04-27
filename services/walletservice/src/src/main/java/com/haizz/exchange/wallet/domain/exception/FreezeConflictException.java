package com.haizz.exchange.wallet.domain.exception;

public class FreezeConflictException extends WalletException {
    public FreezeConflictException(String referenceId) {
        super("FREEZE_CONFLICT",
                "A freeze already exists for referenceId=" + referenceId + " with a different amount.");
    }
}
