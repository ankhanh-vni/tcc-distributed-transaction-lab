package com.tcc.payment.api;

import com.tcc.common.dto.TccErrorResponse;
import com.tcc.common.tcc.TccErrorCode;
import com.tcc.common.tcc.TccException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class TccExceptionHandler {

    @ExceptionHandler(TccException.class)
    public ResponseEntity<TccErrorResponse> handle(TccException ex) {
        HttpStatus status = switch (ex.getCode()) {
            case CONFIRM_AFTER_CANCEL, CANCEL_AFTER_CONFIRM -> HttpStatus.CONFLICT;
            case UNKNOWN_TX -> HttpStatus.NOT_FOUND;
            case BUSINESS_PRECONDITION_FAILED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case INJECTED_FAILURE -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return ResponseEntity.status(status).body(new TccErrorResponse(ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<TccErrorResponse> handleIllegalState(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new TccErrorResponse(TccErrorCode.BUSINESS_PRECONDITION_FAILED, ex.getMessage()));
    }
}
