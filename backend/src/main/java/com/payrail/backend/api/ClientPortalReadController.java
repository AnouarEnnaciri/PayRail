package com.payrail.backend.api;

import com.payrail.backend.account.AccountRepository;
import com.payrail.backend.security.DemoOwnerResolver;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Customer-scoped, read-only views. Ownership checks stay server-side. */
@RestController
@Profile("demo")
@RequestMapping("/api/client/portal")
public class ClientPortalReadController {
    public record TransferView(UUID id, UUID senderAccountId, UUID receiverAccountId,
                               String senderReference, String receiverReference, String destinationReference,
                               String settlementRequestId, long amountMinor,
                               String transferType, String status, Instant createdAt) { }

    public record LedgerView(UUID id, UUID ledgerTransactionId, UUID transferId, String effectType,
                             long signedAmountMinor, String currency, Instant createdAt,
                             String fundingReference) { }
    public record BeneficiaryView(UUID id, String reference) { }

    private static final String TRANSFER_SELECT = """
            SELECT t.id, t.sender_account_id, t.receiver_account_id, t.destination_reference,
                   t.settlement_request_id, t.amount_minor, t.transfer_type, t.status, t.created_at,
                   sender.reference AS sender_reference, recipient.reference AS receiver_reference
            FROM transfers t
            JOIN accounts sender ON sender.id = t.sender_account_id
            LEFT JOIN accounts recipient ON recipient.id = t.receiver_account_id
            """;

    private final JdbcTemplate jdbc;
    private final AccountRepository accounts;
    private final DemoOwnerResolver owners;

    public ClientPortalReadController(JdbcTemplate jdbc, AccountRepository accounts, DemoOwnerResolver owners) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.owners = owners;
    }

    /** Only the two synthetic demo beneficiaries are discoverable; no real customer directory. */
    @GetMapping("/beneficiaries")
    public List<BeneficiaryView> beneficiaries(Principal principal) {
        UUID owner = owners.resolve(principal);
        return jdbc.query("""
                SELECT id, reference FROM accounts
                WHERE account_type='CUSTOMER' AND status='ACTIVE'
                  AND reference IN ('DEMO-CLIENT-ALPHA', 'DEMO-CLIENT-BETA')
                  AND owner_id <> ?
                ORDER BY reference
                """, (rs, row) -> new BeneficiaryView(rs.getObject("id", UUID.class),
                rs.getString("reference")), owner);
    }

    @GetMapping("/transfers")
    public List<TransferView> transfers(Principal principal,
                                        @RequestParam(defaultValue = "100") int limit) {
        UUID owner = owners.resolve(principal);
        return jdbc.query(TRANSFER_SELECT + """
                WHERE sender.owner_id = ? OR recipient.owner_id = ?
                ORDER BY t.created_at DESC, t.id DESC LIMIT ?
                """, ClientPortalReadController::transferRow, owner, owner, bounded(limit));
    }

    @GetMapping("/transfers/{id}")
    public TransferView transfer(@PathVariable UUID id, Principal principal) {
        UUID owner = owners.resolve(principal);
        List<TransferView> results = jdbc.query(TRANSFER_SELECT + """
                WHERE t.id = ? AND (sender.owner_id = ? OR recipient.owner_id = ?)
                """, ClientPortalReadController::transferRow, id, owner, owner);
        if (results.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Transfer not found");
        return results.get(0);
    }

    @GetMapping("/accounts/{id}/ledger")
    public List<LedgerView> ledger(@PathVariable UUID id, Principal principal,
                                   @RequestParam(defaultValue = "100") int limit) {
        UUID owner = owners.resolve(principal);
        if (accounts.findByIdAndOwnerId(id, owner).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found");
        }
        return jdbc.query("""
                SELECT e.id, e.ledger_transaction_id, lt.transfer_id, lt.effect_type,
                       e.signed_amount_minor, e.currency, e.created_at, lt.funding_reference
                FROM ledger_entries e
                JOIN ledger_transactions lt ON lt.id = e.ledger_transaction_id
                WHERE e.account_id = ?
                ORDER BY e.created_at DESC, e.id DESC LIMIT ?
                """, (rs, row) -> new LedgerView(
                rs.getObject("id", UUID.class), rs.getObject("ledger_transaction_id", UUID.class),
                rs.getObject("transfer_id", UUID.class), rs.getString("effect_type"),
                rs.getLong("signed_amount_minor"), rs.getString("currency"),
                timestamp(rs, "created_at"), rs.getString("funding_reference")), id, bounded(limit));
    }

    static int bounded(int limit) {
        if (limit < 1 || limit > 200) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Limit must be 1..200");
        return limit;
    }

    static TransferView transferRow(ResultSet rs, int row) throws SQLException {
        UUID settlementId = rs.getObject("settlement_request_id", UUID.class);
        return new TransferView(rs.getObject("id", UUID.class),
                rs.getObject("sender_account_id", UUID.class), rs.getObject("receiver_account_id", UUID.class),
                rs.getString("sender_reference"), rs.getString("receiver_reference"),
                rs.getString("destination_reference"), settlementId == null ? null : settlementId.toString(),
                rs.getLong("amount_minor"), rs.getString("transfer_type"), rs.getString("status"),
                timestamp(rs, "created_at"));
    }

    static Instant timestamp(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    static String transferSelect() { return TRANSFER_SELECT; }
}
