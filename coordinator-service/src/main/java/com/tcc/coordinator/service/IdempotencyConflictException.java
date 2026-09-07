package com.tcc.coordinator.service;

public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException() {
        super("Idempotency-Key was already used for different order details");
    }
}
