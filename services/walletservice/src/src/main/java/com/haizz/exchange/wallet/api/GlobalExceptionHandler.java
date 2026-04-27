package com.haizz.exchange.wallet.api;

import com.haizz.exchange.wallet.domain.exception.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(InsufficientAvailableBalanceException.class)
    public ResponseEntity<ErrorResponse> handle(InsufficientAvailableBalanceException ex,
                                                 HttpServletRequest req) {
        Map<String, Object> details = Map.of(
                "available", ex.getAvailable(),
                "requested", ex.getRequested(),
                "frozen", ex.getFrozen());
        return respond(HttpStatus.BAD_REQUEST, ex.getCode(), ex.getMessage(), req.getRequestURI(), details);
    }

    @ExceptionHandler(InsufficientFrozenBalanceException.class)
    public ResponseEntity<ErrorResponse> handle(InsufficientFrozenBalanceException ex,
                                                 HttpServletRequest req) {
        return respond(HttpStatus.CONFLICT, ex.getCode(), ex.getMessage(), req.getRequestURI(), null);
    }

    @ExceptionHandler(DepositAmountExceedsLimitException.class)
    public ResponseEntity<ErrorResponse> handle(DepositAmountExceedsLimitException ex,
                                                 HttpServletRequest req) {
        return respond(HttpStatus.BAD_REQUEST, ex.getCode(), ex.getMessage(), req.getRequestURI(), null);
    }

    @ExceptionHandler(DepositAssetNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handle(DepositAssetNotSupportedException ex,
                                                 HttpServletRequest req) {
        return respond(HttpStatus.BAD_REQUEST, ex.getCode(), ex.getMessage(), req.getRequestURI(), null);
    }

    @ExceptionHandler(FreezeConflictException.class)
    public ResponseEntity<ErrorResponse> handle(FreezeConflictException ex, HttpServletRequest req) {
        return respond(HttpStatus.CONFLICT, ex.getCode(), ex.getMessage(), req.getRequestURI(), null);
    }

    @ExceptionHandler(WalletNotFoundException.class)
    public ResponseEntity<ErrorResponse> handle(WalletNotFoundException ex, HttpServletRequest req) {
        return respond(HttpStatus.NOT_FOUND, ex.getCode(), ex.getMessage(), req.getRequestURI(), null);
    }

    @ExceptionHandler(WalletConcurrentModificationException.class)
    public ResponseEntity<ErrorResponse> handle(WalletConcurrentModificationException ex,
                                                 HttpServletRequest req) {
        return respond(HttpStatus.CONFLICT, ex.getCode(), ex.getMessage(), req.getRequestURI(), null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handle(MethodArgumentNotValidException ex,
                                                 HttpServletRequest req) {
        Map<String, Object> details = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        fe -> fe.getField(),
                        fe -> fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "Invalid value",
                        (a, b) -> a));
        ErrorResponse body = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(), "Bad Request", "VALIDATION_FAILED",
                "Request validation failed.", req.getRequestURI(), Instant.now(), details);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex, HttpServletRequest req) {
        log.error("Unhandled exception on {}", req.getRequestURI(), ex);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected error occurred.", req.getRequestURI(), null);
    }

    private ResponseEntity<ErrorResponse> respond(HttpStatus status, String code, String message,
                                                    String path, Map<String, Object> details) {
        ErrorResponse body = new ErrorResponse(
                status.value(), status.getReasonPhrase(), code, message, path, Instant.now(), details);
        return ResponseEntity.status(status).body(body);
    }
}
