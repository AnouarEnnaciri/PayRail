package com.payrail.simulator;

public class SettlementConflictException extends RuntimeException {
    public SettlementConflictException() {
        super("Settlement request ID was already used with a different payload");
    }
}
