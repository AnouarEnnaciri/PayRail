package com.payrail.simulator;

public class SettlementNotFoundException extends RuntimeException {
    public SettlementNotFoundException() {
        super("Settlement request not found");
    }
}
