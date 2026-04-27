package com.haizz.exchange.auth.domain.exception;

public class InvalidRefreshTokenException extends AuthException {

    public InvalidRefreshTokenException(String code) {
        super(code, buildMessage(code));
    }

    private static String buildMessage(String code) {
        return switch (code) {
            case "REFRESH_TOKEN_EXPIRED"  -> "Refresh token has expired.";
            case "REFRESH_TOKEN_REVOKED"  -> "Refresh token has been revoked.";
            default                       -> "Invalid refresh token.";
        };
    }
}
