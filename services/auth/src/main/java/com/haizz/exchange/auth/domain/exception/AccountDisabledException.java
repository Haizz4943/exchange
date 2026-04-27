package com.haizz.exchange.auth.domain.exception;

public class AccountDisabledException extends AuthException {

    public AccountDisabledException() {
        super("ACCOUNT_DISABLED", "This account has been disabled.");
    }
}
