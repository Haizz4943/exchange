package com.haizz.exchange.order.domain.exception;

import com.haizz.exchange.common.web.ServiceUnavailableException;

public class WalletUnavailableException extends ServiceUnavailableException {
    public WalletUnavailableException(String message) {
        super("WALLET_SERVICE_UNAVAILABLE", message);
    }

    public WalletUnavailableException(String message, Throwable cause) {
        super("WALLET_SERVICE_UNAVAILABLE", message + ": " + cause.getMessage());
    }
}
