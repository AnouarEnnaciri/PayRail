# PayRail M9 — Success & Compensation

## Base

Apply ONLY on top of GitHub commit `c5f6e0c` (M8), or a verified equivalent codebase.
The archive uses repository-root paths; extract it at `C:\Users\hp\IdeaProjects\PayRail`, **not** inside `backend/`.

## Scope

- The M8 worker continues to call the simulator **outside** the PostgreSQL transaction.
- On verified `SETTLED`, M9 posts `SETTLEMENT_CLEARING -amount` and `EXTERNAL_SETTLED +amount` and sets the external transfer to `SETTLED`.
- On definitive `REJECTED`, M9 posts `SETTLEMENT_CLEARING -amount` and `CUSTOMER +amount`, credits the customer's materialized balance, and sets the transfer to `FAILED`.
- The settlement attempt, outbox `PROCESSED` marker, financial ledger effect, and transfer transition are **one PostgreSQL transaction**.
- Conditional lease-owner/expiry update fences stale workers. A stale completion fails and its attempt update rolls back.
- Partial unique index `uq_external_terminal_effect_per_transfer` rejects BOTH duplicate finalization and finalization-after-compensation.
- The finalization validates settlement ID, amount and destination against the reserved transfer before moving funds.
- A network timeout, HTTP error, `PENDING` response or DEAD_LETTER job is **not a financial rejection**.
- M10 will handle uncertain outcomes / status reconciliation; M9 does not introduce automatic refund on timeouts.

## Files

Added: `backend/src/main/resources/db/migration/V7__external_settlement_finalization.sql`,
`backend/src/main/java/com/payrail/backend/settlement/ExternalSettlementFinalizationService.java`,
`backend/src/test/java/com/payrail/backend/ExternalSettlementFinalizationIntegrationTest.java`.
Updated: `LedgerEffectType`, `LedgerTransaction`, `Transfer`, `TransferRepository`, `OutboxStore`, and the M8 test whose old expectation of no finalization must change.

## Installation / checkpoint (PowerShell)

```powershell
cd C:\Users\hp\IdeaProjects\PayRail
git status --short
git rev-parse --short HEAD
# Verify no conflicting local changes before expanding the archive into this root.
Expand-Archive -LiteralPath "$HOME\Downloads\PayRail-M9-Success-Compensation.zip" -DestinationPath . -Force
Test-Path .\backend\src\main\resources\db\migration\V7__external_settlement_finalization.sql
Test-Path .\backend\src\test\java\com\payrail\backend\ExternalSettlementFinalizationIntegrationTest.java
cd backend
mvn -B -ntp clean verify
```

Expected: an increase in tests from the **46 passing tests of M8**. Verify actual test count in Maven output; do not assume a particular count until tests execute.

After tests pass, stage precisely the M9 paths, run `git diff --cached --check`, inspect the staged diff/stat, commit and push. GitHub Actions must succeed before M9 is marked complete.

## Verification limitations

The supplied tests use PostgreSQL Testcontainers and a controlled (mocked) external transport to verify financial atomicity. The full Docker backend + simulator cross-service scenario remains an additional integration checkpoint (not claimed here). No test was executed by the generation environment. Do not treat a successful static review as a Maven/Testcontainers success.
