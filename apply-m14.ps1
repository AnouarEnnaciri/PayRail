# Apply after extracting PayRail-M14.zip at the clean repository root.
# Preconditions: main at de587c2, M13 CI green. Does not alter financial posting logic.
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$head = (& git -C $root rev-parse --short HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $head -ne 'de587c2') {
    throw "M14 requires commit de587c2 (current: $head)."
}
$trackedChanges = & git -C $root diff --name-only
if ($LASTEXITCODE -ne 0 -or $trackedChanges) {
    throw 'Tracked working-tree changes detected. Review them before running M14 patch.'
}
$storePath = Join-Path $root 'backend\src\main\java\com\payrail\backend\outbox\OutboxStore.java'
$legacyPath = Join-Path $root 'backend\src\test\java\com\payrail\backend\OutboxWorkerIntegrationTest.java'
$store = [System.IO.File]::ReadAllText($storePath).Replace("`r`n", "`n")
$legacy = [System.IO.File]::ReadAllText($legacyPath).Replace("`r`n", "`n")
if ($store.Contains('private record ExpiredLease(')) {
    throw 'M14 patch appears already applied. Refusing to apply twice.'
}
function Replace-ExactlyOnce([string]$source, [string]$before, [string]$after, [string]$name) {
    $index = $source.IndexOf($before, [System.StringComparison]::Ordinal)
    if ($index -lt 0) { throw "M14 patch anchor '$name' was not found." }
    $again = $source.IndexOf($before, $index + $before.Length, [System.StringComparison]::Ordinal)
    if ($again -ge 0) { throw "M14 patch anchor '$name' matched multiple times." }
    return $source.Substring(0, $index) + $after + $source.Substring($index + $before.Length)
}
$before = @'
    public record Completion(String outcome, Integer httpStatus, String errorCategory,
                             String externalReference, String errorDetail,
                             boolean terminalReceipt, boolean permanentError,
                             long latencyMs) { }

    private final JdbcTemplate jdbc;
'@
$after = @'
    public record Completion(String outcome, Integer httpStatus, String errorCategory,
                             String externalReference, String errorDetail,
                             boolean terminalReceipt, boolean permanentError,
                             long latencyMs) { }

    private record ExpiredLease(UUID eventId, UUID transferId, int attemptNumber) { }

    private final JdbcTemplate jdbc;
'@
$store = Replace-ExactlyOnce $store $before $after 'expired lease record'

$before = @'
        List<UUID> exhaustedTransfers = jdbc.query("""
                UPDATE outbox_events
                SET status = 'DEAD_LETTER', locked_until = NULL, locked_by = NULL,
                    last_error = 'Lease expired after retry budget'
                WHERE event_type = 'SETTLEMENT_REQUESTED'
                  AND status = 'PROCESSING' AND locked_until <= now()
                  AND attempt_count >= ?
                RETURNING aggregate_id
                """, (rs, row) -> rs.getObject("aggregate_id", UUID.class), maxAttempts);
        for (UUID transferId : exhaustedTransfers) {
            markReconciliationRequired(transferId);
        }
'@
$after = @'
        List<ExpiredLease> exhaustedTransfers = jdbc.query("""
                UPDATE outbox_events
                SET status = 'DEAD_LETTER', locked_until = NULL, locked_by = NULL,
                    last_error = 'Lease expired after retry budget'
                WHERE event_type = 'SETTLEMENT_REQUESTED'
                  AND status = 'PROCESSING' AND locked_until <= now()
                  AND attempt_count >= ?
                RETURNING id, aggregate_id, attempt_count
                """, (rs, row) -> new ExpiredLease(
                        rs.getObject("id", UUID.class),
                        rs.getObject("aggregate_id", UUID.class),
                        rs.getInt("attempt_count")), maxAttempts);
        for (ExpiredLease expired : exhaustedTransfers) {
            closeExpiredAttempt(expired.eventId(), expired.attemptNumber());
            markReconciliationRequired(expired.transferId());
        }
'@
$store = Replace-ExactlyOnce $store $before $after 'final lease exhaustion audit'

$before = @'
        if (results.isEmpty()) return null;
        Claim claim = results.get(0);
        jdbc.update("""
'@
$after = @'
        if (results.isEmpty()) return null;
        Claim claim = results.get(0);
        // A reclaimed lease supersedes the prior in-flight attempt, never a ledger effect.
        // For an ordinary PENDING retry the previous attempt is already completed (0 rows updated).
        if (claim.attemptNumber() > 1) {
            closeExpiredAttempt(claim.eventId(), claim.attemptNumber() - 1);
        }
        jdbc.update("""
'@
$store = Replace-ExactlyOnce $store $before $after 'attempt audit on reclaim'

$before = @'
    private void markReconciliationRequired(UUID transferId) {
'@
$after = @'
    private void closeExpiredAttempt(UUID eventId, int attemptNumber) {
        // The old worker may have died after sending the request. This outcome is technical,
        // NOT a financial rejection and NOT evidence the participant failed to settle.
        jdbc.update("""
                UPDATE settlement_attempts
                SET outcome = 'LEASE_EXPIRED', completed_at = now(),
                    error_category = 'WORKER_LEASE_EXPIRED',
                    last_error = 'Worker lease expired without a durable completion'
                WHERE outbox_event_id = ? AND attempt_number = ? AND outcome = 'IN_FLIGHT'
                """, eventId, attemptNumber);
    }

    private void markReconciliationRequired(UUID transferId) {
'@
$store = Replace-ExactlyOnce $store $before $after 'expired attempt method'

$before = @'
        assertEquals("IN_FLIGHT", jdbc.queryForObject(
                "SELECT outcome FROM settlement_attempts WHERE id = ?", String.class, abandoned.attemptId()));
'@
$after = @'
        assertEquals("LEASE_EXPIRED", jdbc.queryForObject(
                "SELECT outcome FROM settlement_attempts WHERE id = ?", String.class, abandoned.attemptId()));
'@
$legacy = Replace-ExactlyOnce $legacy $before $after 'legacy M8 expectation'

$utf8 = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($storePath, $store, $utf8)
[System.IO.File]::WriteAllText($legacyPath, $legacy, $utf8)
Write-Host 'M14 patch applied to OutboxStore.java and the legacy OutboxWorkerIntegrationTest.java.'
Write-Host 'Next: run targeted Testcontainers tests. Do not commit before full validation.'
