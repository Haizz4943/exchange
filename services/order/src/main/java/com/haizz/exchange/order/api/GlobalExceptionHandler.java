package com.haizz.exchange.order.api;

import com.haizz.exchange.common.web.BaseException;
import com.haizz.exchange.common.web.ValidationException;
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

    /** Maps any domain/common exception to its declared HTTP status. */
    @ExceptionHandler(BaseException.class)
    public ResponseEntity<ErrorResponse> handle(BaseException ex, HttpServletRequest req) {
        Map<String, Object> details = null;
        if (ex instanceof ValidationException ve && ve.getFieldErrors() != null) {
            details = ve.getFieldErrors().entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, e -> (Object) e.getValue()));
        }
        HttpStatus status = HttpStatus.valueOf(ex.getHttpStatus());
        return respond(status, ex.getErrorCode(), ex.getMessage(), req.getRequestURI(), details);
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
