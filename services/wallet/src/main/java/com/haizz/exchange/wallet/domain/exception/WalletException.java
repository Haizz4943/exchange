package com.haizz.exchange.wallet.domain.exception;

import lombok.Getter;

@Getter
public class WalletException extends RuntimeException {
    private final String code;

    public WalletException(String code, String message) {
        super(message);
        this.code = code;
    }
}
