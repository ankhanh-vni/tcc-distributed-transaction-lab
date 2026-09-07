package com.tcc.coordinator.api;

import com.tcc.common.dto.TccErrorResponse;
import com.tcc.common.tcc.TccErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(com.tcc.coordinator.service.IdempotencyConflictException.class)
    public ResponseEntity<java.util.Map<String, String>> handleIdempotencyConflict(
            com.tcc.coordinator.service.IdempotencyConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(java.util.Map.of("code", "IDEMPOTENCY_KEY_REUSED", "message", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<TccErrorResponse> handleNotFound(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new TccErrorResponse(TccErrorCode.UNKNOWN_TX, ex.getMessage()));
    }
}

