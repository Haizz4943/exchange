package com.haizz.exchange.auth.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        int status,
        String error,
        String code,
        String message,
        String path,
        Instant timestamp,
        Map<String, String> details
) {
}
