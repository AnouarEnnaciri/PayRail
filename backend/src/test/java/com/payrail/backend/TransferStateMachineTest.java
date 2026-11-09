package com.payrail.backend;

import com.payrail.backend.transfer.Transfer;
import com.payrail.backend.transfer.TransferState;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Pure domain tests. No Spring context, database or Docker required. */
class TransferStateMachineTest {

    private Transfer external() {
        return Transfer.pendingExternal(UUID.randomUUID(), "BANK-M11-001", 20_000L, UUID.randomUUID());
    }

    @Test
    void everyAllowedExternalTransitionIsExplicit() {
        for (TransferState target : new TransferState[]{
                TransferState.SETTLED, TransferState.FAILED, TransferState.RECONCILIATION_REQUIRED}) {
            assertTrue(TransferState.PENDING_SETTLEMENT.allows(target));
        }
        assertTrue(TransferState.RECONCILIATION_REQUIRED.allows(TransferState.SETTLED));
        assertTrue(TransferState.RECONCILIATION_REQUIRED.allows(TransferState.FAILED));
    }

    @Test
    void noTerminalStateMayBeReopenedOrReplayed() {
        for (TransferState terminal : new TransferState[]{
                TransferState.COMPLETED, TransferState.SETTLED, TransferState.FAILED}) {
            assertTrue(terminal.isTerminal());
            for (TransferState target : TransferState.values()) {
                assertFalse(terminal.allows(target), terminal + " unexpectedly permits " + target);
            }
        }
    }

    @Test
    void onlyPendingMayEscalateUncertainty() {
        assertFalse(TransferState.RECONCILIATION_REQUIRED.allows(TransferState.PENDING_SETTLEMENT));
        assertFalse(TransferState.RECONCILIATION_REQUIRED.allows(TransferState.RECONCILIATION_REQUIRED));
        assertFalse(TransferState.PENDING_SETTLEMENT.allows(TransferState.PENDING_SETTLEMENT));
        assertFalse(TransferState.PENDING_SETTLEMENT.allows(TransferState.COMPLETED));
    }

    @Test
    void verifiedSuccessAndRejectionCanFinishPendingTransfer() {
        Transfer success = external();
        success.requireExternalFinalizationAllowed();
        success.markSettled();
        assertEquals("SETTLED", success.getStatus());
        assertThrows(IllegalStateException.class, success::markSettled);
        assertThrows(IllegalStateException.class, success::markFailedAfterDefinitiveRejection);
        assertThrows(IllegalStateException.class, success::markReconciliationRequired);

        Transfer rejection = external();
        rejection.markFailedAfterDefinitiveRejection();
        assertEquals("FAILED", rejection.getStatus());
        assertThrows(IllegalStateException.class, rejection::markSettled);
    }

    @Test
    void uncertainTransferMayRecoverToOneTerminalOutcome() {
        Transfer success = external();
        success.markReconciliationRequired();
        success.requireExternalFinalizationAllowed();
        success.markSettled();
        assertEquals("SETTLED", success.getStatus());
        assertThrows(IllegalStateException.class, success::markFailedAfterDefinitiveRejection);

        Transfer rejected = external();
        rejected.markReconciliationRequired();
        rejected.markFailedAfterDefinitiveRejection();
        assertEquals("FAILED", rejected.getStatus());
        assertThrows(IllegalStateException.class, rejected::markSettled);
    }

    @Test
    void uncertaintyEscalationNeverChangesAmountOrSettlementIdentity() {
        Transfer transfer = external();
        long amount = transfer.getAmountMinor();
        UUID id = transfer.getSettlementRequestId();
        transfer.markReconciliationRequired();
        assertEquals(amount, transfer.getAmountMinor());
        assertEquals(id, transfer.getSettlementRequestId());
        assertThrows(IllegalStateException.class, transfer::markReconciliationRequired);
    }

    @Test
    void internalTransferIsBornTerminalAndRejectsExternalTransitions() {
        Transfer internal = Transfer.completed(UUID.randomUUID(), UUID.randomUUID(), 500L);
        assertEquals("COMPLETED", internal.getStatus());
        assertThrows(IllegalStateException.class, internal::markSettled);
        assertThrows(IllegalStateException.class, internal::markFailedAfterDefinitiveRejection);
        assertThrows(IllegalStateException.class, internal::markReconciliationRequired);
        assertThrows(IllegalStateException.class, internal::requireExternalFinalizationAllowed);
    }
}
