package com.haizz.exchange.common.web;

import java.time.Instant;
import java.util.Map;

public record ErrorResponse(
        String code,
        String message,
        String correlationId,
        Instant timestamp,
        Map<String, String> details
) {
    public static ErrorResponse of(String code, String message, String correlationId) {
        return new ErrorResponse(code, message, correlationId, Instant.now(), null);
    }

    public static ErrorResponse of(String code, String message, String correlationId, Map<String, String> details) {
        return new ErrorResponse(code, message, correlationId, Instant.now(), details);
    }
}
