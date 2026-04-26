package com.haizz.exchange.auth.domain.exception;

public class ValidationException extends AuthException {

    public ValidationException(String code, String message) {
        super(code, message);
    }
}
