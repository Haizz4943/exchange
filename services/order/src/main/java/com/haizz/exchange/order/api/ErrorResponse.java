package com.haizz.exchange.order.api;

import java.time.Instant;
import java.util.Map;

public record ErrorResponse(
        int status,
        String error,
        String errorCode,
        String message,
        String path,
        Instant timestamp,
        Map<String, Object> details
) {}
