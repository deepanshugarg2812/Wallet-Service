package com.main.wallet.exception;

public class IdempotencyConflictException
        extends RuntimeException {

    public IdempotencyConflictException(String message) {
        super(message);
    }
}
