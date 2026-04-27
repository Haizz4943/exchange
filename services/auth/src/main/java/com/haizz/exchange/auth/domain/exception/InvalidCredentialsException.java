package com.haizz.exchange.auth.domain.exception;

public class InvalidCredentialsException extends AuthException {

    public InvalidCredentialsException() {
        super("INVALID_CREDENTIALS", "Invalid email or password.");
    }
}
