package com.payrail.backend.outbox;

import com.payrail.backend.settlement.SettlementTransport;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.Objects;

/** Calls the external simulator BETWEEN two short, committed DB transactions. */
@Service
public class OutboxProcessor {

    private final OutboxStore store;
    private final SettlementTransport transport;

    public OutboxProcessor(OutboxStore store, SettlementTransport transport) {
        this.store = store;
        this.transport = transport;
    }

    /** @return true when one eligible event was claimed; false when nothing was due. */
    public boolean processOne() {
        OutboxStore.Claim claim = store.claimNext(); // commit before HTTP
        if (claim == null) return false;

        long start = System.nanoTime();
        OutboxStore.Completion completion;
        try {
            SettlementTransport.Command command = new SettlementTransport.Command(
                    claim.settlementRequestId(), claim.amountMinor(),
                    claim.destinationReference(), claim.mode());
            SettlementTransport.Receipt receipt = transport.submit(command);
            verify(receipt, claim);
            if ("PENDING".equals(receipt.status())) {
                // DELAYED_SUCCESS is advanced by the simulator's GET handler, not its POST.
                receipt = transport.get(claim.settlementRequestId());
                verify(receipt, claim);
            }
            String status = receipt.status();
            boolean terminal = "SETTLED".equals(status) || "REJECTED".equals(status);
            completion = new OutboxStore.Completion(
                    status, 200, null, receipt.externalReference(),
                    terminal ? null : "External participant still pending",
                    terminal, false, elapsedMs(start));
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            // 409 = protocol/idempotency conflict. Not evidence of financial rejection.
            boolean permanent = status == 409 || (status >= 400 && status < 500 && status != 408 && status != 429);
            completion = new OutboxStore.Completion(
                    "HTTP_ERROR", status, "HTTP_" + status, null,
                    "Simulator HTTP status " + status, false, permanent, elapsedMs(start));
        } catch (ResourceAccessException ex) {
            // Timeout may have happened AFTER the simulator committed the settlement.
            completion = new OutboxStore.Completion(
                    "NETWORK_ERROR", null, "NETWORK_OR_TIMEOUT", null,
                    ex.getClass().getSimpleName(), false, false, elapsedMs(start));
        } catch (RestClientException ex) {
            completion = new OutboxStore.Completion(
                    "NETWORK_ERROR", null, "REST_CLIENT_ERROR", null,
                    ex.getClass().getSimpleName(), false, false, elapsedMs(start));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            completion = new OutboxStore.Completion(
                    "INVALID_RESPONSE", null, "INVALID_RESPONSE", null,
                    ex.getMessage(), false, false, elapsedMs(start));
        }
        store.complete(claim, completion); // a second DB transaction
        return true;
    }

    private static void verify(SettlementTransport.Receipt receipt, OutboxStore.Claim claim) {
        if (receipt == null
                || !Objects.equals(receipt.settlementRequestId(), claim.settlementRequestId())
                || receipt.amountMinor() != claim.amountMinor()
                || !Objects.equals(receipt.destinationReference(), claim.destinationReference())
                || !("SETTLED".equals(receipt.status())
                    || "REJECTED".equals(receipt.status())
                    || "PENDING".equals(receipt.status()))
                || ("SETTLED".equals(receipt.status())
                    && (receipt.externalReference() == null || receipt.externalReference().isBlank()))) {
            throw new IllegalStateException("Invalid or mismatched simulator receipt");
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
