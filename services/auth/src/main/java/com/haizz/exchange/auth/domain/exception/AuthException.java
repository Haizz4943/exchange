package com.haizz.exchange.auth.domain.exception;

public abstract class AuthException extends RuntimeException {

    private final String code;

    protected AuthException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
