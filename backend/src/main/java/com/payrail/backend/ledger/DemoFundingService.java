package com.payrail.backend.ledger;

import com.payrail.backend.account.Account;
import com.payrail.backend.account.AccountRepository;
import com.payrail.backend.account.AccountStatus;
import com.payrail.backend.account.AccountType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@Profile("demo")
public class DemoFundingService {
    private static final Logger log = LoggerFactory.getLogger(DemoFundingService.class);
    private static final String SYSTEM_REFERENCE = "DEMO-SYSTEM-FUNDING";
    private static final Set<String> ALLOWED_ACCOUNTS = Set.of("DEMO-CLIENT-ALPHA", "DEMO-CLIENT-BETA");
    private static final long MAX_AMOUNT_MINOR = 1_000_000L; // 10,000 MAD per request

    private final JdbcTemplate jdbc;
    private final AccountRepository accounts;
    private final LedgerPostingService posting;
    private final LedgerTransactionRepository ledgerTransactions;

    public record FundingResult(UUID requestId, UUID ledgerTransactionId, String accountReference,
                                long amountMinor, long balanceAfterMinor, boolean replayed) { }
    public record DemoAccount(String reference, long balanceMinor, String status) { }
    private record StoredRequest(String accountReference, long amountMinor, String initiatedBy,
                                 UUID ledgerTransactionId, Long balanceAfterMinor) { }

    public DemoFundingService(JdbcTemplate jdbc, AccountRepository accounts,
                              LedgerPostingService posting, LedgerTransactionRepository ledgerTransactions) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.posting = posting;
        this.ledgerTransactions = ledgerTransactions;
    }

    @Transactional(readOnly = true)
    public List<DemoAccount> accounts() {
        return List.of("DEMO-CLIENT-ALPHA", "DEMO-CLIENT-BETA").stream()
                .map(reference -> accounts.findByReference(reference)
                        .map(a -> new DemoAccount(a.getReference(), a.getBalanceMinor(), a.getStatus().name()))
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                                "Demo account unavailable")))
                .toList();
    }

    /** Atomic, durable, repeatable top-up with a separately recorded idempotency request. */
    @Transactional
    public FundingResult fund(String accountReference, Long amountMinor, UUID requestId, String actor) {
        if (requestId == null || actor == null || actor.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency key and operator are required");
        }
        if (!ALLOWED_ACCOUNTS.contains(accountReference)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown demo account");
        }
        if (amountMinor == null || amountMinor <= 0 || amountMinor > MAX_AMOUNT_MINOR) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Amount must be between 1 and 1000000 minor units");
        }

        // PostgreSQL ON CONFLICT waits for an in-flight request with this id.
        // A retry only sees a committed result. Losing writers never post money.
        int reserved = jdbc.update("""
                INSERT INTO demo_funding_requests(request_id, account_reference, amount_minor, initiated_by)
                VALUES (?, ?, ?, ?) ON CONFLICT (request_id) DO NOTHING
                """, requestId, accountReference, amountMinor, actor);

        if (reserved == 0) {
            StoredRequest stored = jdbc.queryForObject("""
                    SELECT account_reference, amount_minor, initiated_by,
                           ledger_transaction_id, balance_after_minor
                    FROM demo_funding_requests WHERE request_id = ?
                    """, (rs, row) -> new StoredRequest(rs.getString("account_reference"),
                    rs.getLong("amount_minor"), rs.getString("initiated_by"),
                    rs.getObject("ledger_transaction_id", UUID.class),
                    rs.getObject("balance_after_minor", Long.class)), requestId);
            if (stored == null || !accountReference.equals(stored.accountReference())
                    || amountMinor.longValue() != stored.amountMinor() || !actor.equals(stored.initiatedBy())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key already used for a different request");
            }
            if (stored.ledgerTransactionId() == null || stored.balanceAfterMinor() == null) {
                throw new IllegalStateException("Committed demo funding request is incomplete");
            }
            return new FundingResult(requestId, stored.ledgerTransactionId(), accountReference,
                    amountMinor, stored.balanceAfterMinor(), true);
        }

        Account customer = accounts.findByReference(accountReference)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Demo account not found"));
        // Serialize balance changes with transfers and other funding operations.
        customer = accounts.findByIdForUpdate(customer.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Demo account not found"));
        Account source = accounts.findByReference(SYSTEM_REFERENCE)
                .orElseThrow(() -> new IllegalStateException("Demo funding system account not found"));
        if (customer.getAccountType() != AccountType.CUSTOMER || customer.getStatus() != AccountStatus.ACTIVE
                || source.getAccountType() != AccountType.SYSTEM || source.getStatus() != AccountStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Account is not active or its type is invalid");
        }

        LedgerTransaction tx = LedgerTransaction.demoFunding("DEMO-TOPUP:" + requestId);
        LedgerEntry debit = LedgerEntry.create(tx.getId(), source.getId(), -amountMinor);
        LedgerEntry credit = LedgerEntry.create(tx.getId(), customer.getId(), amountMinor);
        customer.credit(amountMinor);
        posting.post(tx, List.of(debit, credit));
        // Ensure FK ledger_transaction_id can see the JPA insert before the JDBC UPDATE.
        ledgerTransactions.flush();
        long balanceAfter = customer.getBalanceMinor();
        int finished = jdbc.update("""
                UPDATE demo_funding_requests
                SET ledger_transaction_id = ?, balance_after_minor = ? WHERE request_id = ?
                """, tx.getId(), balanceAfter, requestId);
        if (finished != 1) throw new IllegalStateException("Demo funding request not finalized");
        log.info("DEMO_FUNDING operator={} account={} amountMinor={} requestId={} txId={}",
                actor, accountReference, amountMinor, requestId, tx.getId());
        return new FundingResult(requestId, tx.getId(), accountReference, amountMinor, balanceAfter, false);
    }
}
