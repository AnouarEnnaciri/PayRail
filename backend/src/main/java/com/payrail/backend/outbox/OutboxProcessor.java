package com.payrail.backend.outbox;

import com.payrail.backend.settlement.SettlementTransport;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.Objects;

/** Makes network calls only between committed, short PostgreSQL transactions. */
@Service
public class OutboxProcessor {

    private final OutboxStore store;
    private final SettlementTransport transport;

    public OutboxProcessor(OutboxStore store, SettlementTransport transport) {
        this.store = store;
        this.transport = transport;
    }

    /** @return true if one due event was claimed, false otherwise. */
    public boolean processOne() {
        OutboxStore.Claim claim = store.claimNext(); // commits BEFORE any HTTP request
        if (claim == null) return false;

        long started = System.nanoTime();
        OutboxStore.Completion completion;
        try {
            SettlementTransport.Command command = new SettlementTransport.Command(
                    claim.settlementRequestId(), claim.amountMinor(),
                    claim.destinationReference(), claim.mode());
            SettlementTransport.Receipt receipt = transport.submit(command);
            verify(receipt, claim);
            if ("PENDING".equals(receipt.status())) {
                receipt = transport.get(claim.settlementRequestId());
                verify(receipt, claim);
            }
            completion = receiptCompletion(receipt, started);
        } catch (RestClientResponseException ex) {
            int code = ex.getStatusCode().value();
            // A 408, 429 or 5xx may have arrived AFTER the external operation committed.
            // Query the SAME stable request ID before scheduling another attempt.
            if (code == 408 || code == 429 || code >= 500) {
                completion = probeAfterUncertainResponse(claim, started);
            } else {
                completion = new OutboxStore.Completion(
                        "HTTP_ERROR", code, "HTTP_" + code, null,
                        "Simulator HTTP status " + code, false, true, elapsedMs(started));
            }
        } catch (ResourceAccessException ex) {
            // Network failure / read timeout does not distinguish before from after processing.
            completion = probeAfterUncertainResponse(claim, started);
        } catch (RestClientException ex) {
            completion = new OutboxStore.Completion(
                    "NETWORK_ERROR", null, "REST_CLIENT_ERROR", null,
                    ex.getClass().getSimpleName(), false, false, elapsedMs(started));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            completion = new OutboxStore.Completion(
                    "INVALID_RESPONSE", null, "INVALID_RESPONSE", null,
                    ex.getMessage(), false, false, elapsedMs(started));
        }
        store.complete(claim, completion); // a second, fenced PostgreSQL transaction
        return true;
    }

    /** Querying NOT_FOUND is evidence of absence NOW, never proof of definitive rejection. */
    private OutboxStore.Completion probeAfterUncertainResponse(OutboxStore.Claim claim, long started) {
        try {
            SettlementTransport.Receipt receipt = transport.get(claim.settlementRequestId());
            verify(receipt, claim);
            return receiptCompletion(receipt, started);
        } catch (RestClientResponseException ex) {
            int code = ex.getStatusCode().value();
            if (code == 404) {
                return new OutboxStore.Completion(
                        "NOT_FOUND", 404, "STATUS_LOOKUP_NOT_FOUND", null,
                        "No external record at lookup time; retry same settlement request ID",
                        false, false, elapsedMs(started));
            }
            return new OutboxStore.Completion(
                    "HTTP_ERROR", code, "STATUS_LOOKUP_HTTP_" + code, null,
                    "Status lookup returned HTTP " + code,
                    false, false, elapsedMs(started));
        } catch (ResourceAccessException ex) {
            return new OutboxStore.Completion(
                    "NETWORK_ERROR", null, "STATUS_LOOKUP_NETWORK_OR_TIMEOUT", null,
                    ex.getClass().getSimpleName(), false, false, elapsedMs(started));
        } catch (RestClientException ex) {
            return new OutboxStore.Completion(
                    "NETWORK_ERROR", null, "STATUS_LOOKUP_CLIENT_ERROR", null,
                    ex.getClass().getSimpleName(), false, false, elapsedMs(started));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return new OutboxStore.Completion(
                    "INVALID_RESPONSE", null, "STATUS_LOOKUP_INVALID_RESPONSE", null,
                    ex.getMessage(), false, false, elapsedMs(started));
        }
    }

    private static OutboxStore.Completion receiptCompletion(SettlementTransport.Receipt receipt, long started) {
        String status = receipt.status();
        boolean terminal = "SETTLED".equals(status) || "REJECTED".equals(status);
        return new OutboxStore.Completion(
                status, 200, null, receipt.externalReference(),
                terminal ? null : "External participant still pending",
                terminal, false, elapsedMs(started));
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
