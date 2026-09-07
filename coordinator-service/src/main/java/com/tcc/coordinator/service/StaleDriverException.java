package com.tcc.coordinator.service;

public class StaleDriverException extends RuntimeException {
    public StaleDriverException() { super("Transaction driver no longer owns an active lease"); }
}
