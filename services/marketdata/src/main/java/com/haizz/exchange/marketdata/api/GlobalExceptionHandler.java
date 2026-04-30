package com.haizz.exchange.marketdata.api;

import com.haizz.exchange.common.web.BaseException;
import com.haizz.exchange.common.web.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BaseException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleBaseException(BaseException ex, ServerWebExchange exchange) {
        String correlationId = exchange.getRequest().getHeaders()
                .getFirst("X-Correlation-Id");
        log.warn("Business exception [{}]: {}", ex.getErrorCode(), ex.getMessage());
        var body = ErrorResponse.of(ex.getErrorCode(), ex.getMessage(), correlationId);
        return Mono.just(ResponseEntity.status(ex.getHttpStatus()).body(body));
    }

    @ExceptionHandler(Exception.class)
    public Mono<ResponseEntity<ErrorResponse>> handleGeneric(Exception ex, ServerWebExchange exchange) {
        String correlationId = exchange.getRequest().getHeaders()
                .getFirst("X-Correlation-Id");
        log.error("Unhandled exception", ex);
        var body = ErrorResponse.of("INTERNAL_ERROR", "An unexpected error occurred", correlationId);
        return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body));
    }
}
