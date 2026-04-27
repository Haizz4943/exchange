package com.haizz.exchange.auth.domain.exception;

public class SsoNotEnabledException extends AuthException {

    public SsoNotEnabledException() {
        super("SSO_NOT_ENABLED", "SSO authentication is not enabled in this environment.");
    }
}
