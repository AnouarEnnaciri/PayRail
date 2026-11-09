# PayRail M10 — Timeouts & Uncertain Outcomes

Baseline: GitHub commit `4d12f57` (M9). Scope: backend only; no simulator changes.

## Design

- A POST timeout or ambiguous HTTP 408/429/5xx triggers a GET using the *same* stable `settlementRequestId`.
- Verified SETTLED/REJECTED receipts use the existing M9 finalization transaction, with exact request-ID, amount and destination validation.
- GET 404 is recorded as `NOT_FOUND`, never `FAILED`; subsequent attempts resend the same persisted outbox payload and ID.
- Exhausted retry budget or permanent protocol error sets **both** `outbox_events.status = DEAD_LETTER` and `transfers.status = RECONCILIATION_REQUIRED` in the same PostgreSQL transaction.
- A worker crash after the final claimed attempt is handled by the expired-lease path with the same escalation.
- No new ledger entry, balance mutation, or automatic compensation occurs for an unknown external outcome.
- The simulator from M6 already implements timeout-before-processing and timeout-after-processing; no change is required.

## Integration on Windows PowerShell

From `C:\Users\hp\IdeaProjects\PayRail`, BEFORE applying the ZIP:

```powershell
cd C:\Users\hp\IdeaProjects\PayRail
git rev-parse --short HEAD
git status --short
```

Require `4d12f57` and a **clean** working tree. Download `PayRail-M10.zip`, then:

```powershell
Expand-Archive -LiteralPath "$env:USERPROFILE\Downloads\PayRail-M10.zip" -DestinationPath . -Force
.\apply-m10-existing-tests.ps1
git diff --check
git status --short
```

If PowerShell execution policy blocks this script, invoke `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\apply-m10-existing-tests.ps1` *only after inspecting the bundled script*.

## Tests (Docker Desktop running)

```powershell
Push-Location .\backend
.\mvnw.cmd "-Dtest=M10UncertainOutcomeIntegrationTest,OutboxWorkerIntegrationTest,ExternalSettlementFinalizationIntegrationTest" test
.\mvnw.cmd clean verify
Pop-Location
Push-Location .\settlement-simulator
mvn -B -ntp clean verify
Pop-Location
```

Do not declare validation before actual `BUILD SUCCESS` for these commands. The eight M10 Testcontainers tests cover successful recovery, HTTP 504 recovery, verified rejection, invalid-receipt safety, `NOT_FOUND`/same-ID retry, retry exhaustion, conflict escalation, and final-attempt lease expiry.

## Commit and CI

Once all tests pass:

```powershell
Remove-Item .\apply-m10-existing-tests.ps1
git add backend README-M10-INTEGRATION.md
git diff --cached --check
git commit -m "feat: recover uncertain external settlements and escalate retry exhaustion M10"
git push origin main
```

Then verify the actual GitHub Actions run for the **new commit** at https://github.com/AnouarEnnaciri/PayRail/actions and confirm that both Maven jobs and both Docker builds pass.

## Follow-on boundary

Do not implement manual resolution or a generalized state machine in this milestone. M11 hardens state transitions; M13 handles verified reconciliation/manual resolution. PayRail Mastery remains post-M19.
