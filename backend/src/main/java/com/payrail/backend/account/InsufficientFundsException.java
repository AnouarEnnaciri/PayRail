
package com.payrail.backend.account;

public class InsufficientFundsException
        extends IllegalStateException {

    public InsufficientFundsException() {
        super("Insufficient funds");
    }
}
