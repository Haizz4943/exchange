package com.haizz.exchange.gateway.jwt;

/**
 * Thrown when JWT verification fails. Carries an error code for the API response.
 */
public class JwtException extends RuntimeException {

    private final String code;

    public JwtException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
