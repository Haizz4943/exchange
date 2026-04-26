package com.haizz.exchange.auth.domain.exception;

public class EmailAlreadyExistsException extends AuthException {

    public EmailAlreadyExistsException() {
        super("EMAIL_ALREADY_EXISTS", "Email address is already registered.");
    }
}
