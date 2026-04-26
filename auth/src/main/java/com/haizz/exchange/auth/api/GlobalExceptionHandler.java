package com.haizz.exchange.auth.api;

import com.haizz.exchange.auth.domain.exception.*;
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

    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handle(EmailAlreadyExistsException ex, HttpServletRequest req) {
        return respond(HttpStatus.CONFLICT, ex.getCode(), ex.getMessage(), req.getRequestURI());
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ErrorResponse> handle(InvalidCredentialsException ex, HttpServletRequest req) {
        return respond(HttpStatus.UNAUTHORIZED, ex.getCode(), ex.getMessage(), req.getRequestURI());
    }

    @ExceptionHandler(AccountDisabledException.class)
    public ResponseEntity<ErrorResponse> handle(AccountDisabledException ex, HttpServletRequest req) {
        return respond(HttpStatus.FORBIDDEN, ex.getCode(), ex.getMessage(), req.getRequestURI());
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ResponseEntity<ErrorResponse> handle(InvalidRefreshTokenException ex, HttpServletRequest req) {
        return respond(HttpStatus.UNAUTHORIZED, ex.getCode(), ex.getMessage(), req.getRequestURI());
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handle(RateLimitExceededException ex, HttpServletRequest req) {
        return respond(HttpStatus.TOO_MANY_REQUESTS, ex.getCode(), ex.getMessage(), req.getRequestURI());
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ErrorResponse> handle(ValidationException ex, HttpServletRequest req) {
        return respond(HttpStatus.BAD_REQUEST, ex.getCode(), ex.getMessage(), req.getRequestURI());
    }

    @ExceptionHandler(SsoNotEnabledException.class)
    public ResponseEntity<ErrorResponse> handle(SsoNotEnabledException ex, HttpServletRequest req) {
        return respond(HttpStatus.NOT_IMPLEMENTED, ex.getCode(), ex.getMessage(), req.getRequestURI());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handle(MethodArgumentNotValidException ex, HttpServletRequest req) {
        Map<String, String> details = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        fe -> fe.getField(),
                        fe -> fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "Invalid value",
                        (a, b) -> a
                ));
        ErrorResponse body = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "Bad Request",
                "VALIDATION_FAILED",
                "Request validation failed.",
                req.getRequestURI(),
                Instant.now(),
                details
        );
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex, HttpServletRequest req) {
        log.error("Unhandled exception on {}", req.getRequestURI(), ex);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected error occurred.", req.getRequestURI());
    }

    private ResponseEntity<ErrorResponse> respond(HttpStatus status, String code,
                                                    String message, String path) {
        ErrorResponse body = new ErrorResponse(
                status.value(), status.getReasonPhrase(), code, message, path, Instant.now(), null);
        return ResponseEntity.status(status).body(body);
    }
}
