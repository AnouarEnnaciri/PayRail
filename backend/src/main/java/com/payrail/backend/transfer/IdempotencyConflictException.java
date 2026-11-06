
package com.payrail.backend.transfer;

public class IdempotencyConflictException
        extends IllegalArgumentException {

    public IdempotencyConflictException(String message) {
        super(message);
    }
}
