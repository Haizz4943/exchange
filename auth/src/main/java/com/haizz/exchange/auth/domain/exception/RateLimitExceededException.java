package com.haizz.exchange.auth.domain.exception;

public class RateLimitExceededException extends AuthException {

    public RateLimitExceededException() {
        super("RATE_LIMIT_EXCEEDED", "Too many requests. Please try again later.");
    }
}
