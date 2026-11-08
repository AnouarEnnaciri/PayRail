# PayRail M6 — Durable Settlement Simulator

External unreliable participant, **not** an internal PayRail microservice. It does not modify the PayRail ledger.

## Guarantees

- One durable logical settlement per UUID via PostgreSQL primary key and atomic `INSERT ... ON CONFLICT DO NOTHING`.
- Replaying the same UUID and identical payload returns the same result; a different payload returns HTTP 409.
- Persisted settlement outcomes and a stable external reference; no random outcome changes.
- `DELAYED_SUCCESS` stores a deadline and settles when queried after that deadline (survives restarts).
- `TIMEOUT_BEFORE_PROCESSING` sleeps longer than the caller timeout **without committing**; subsequent GET is 404.
- `TIMEOUT_AFTER_PROCESSING` **commits SETTLED before** sleeping; GET subsequently returns SETTLED, and replay responds immediately.
- A 504 returned after the sleep is only a fallback for long-waiting clients: configure an HTTP read timeout shorter than the simulator sleep to reproduce a true client-side network timeout.
- No monetary compensation occurs in the simulator. PayRail M7–M10 will decide financial outcomes based on confirmed evidence.

## Run locally (PowerShell from PayRail project root)

Existing `.env` at project root is required. No new secret files.

```powershell
.\start-m6.ps1
```

Compose launches PayRail, its PostgreSQL, the settlement simulator, and a **separate** PostgreSQL database with persistent named volume. Simulator is bound only to `127.0.0.1:8081` on the host.

## Example commands (PowerShell)

```powershell
$id = [guid]::NewGuid().ToString()
$body = @{ settlementRequestId = $id; amountMinor = 50000; destinationReference = 'BANK-DEMO-001'; mode = 'SUCCESS' } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri 'http://localhost:8081/settlements' -ContentType 'application/json' -Body $body
Invoke-RestMethod -Uri "http://localhost:8081/settlements/$id"
```

Modes: `SUCCESS`, `DELAYED_SUCCESS`, `REJECTED`, `TIMEOUT_BEFORE_PROCESSING`, `TIMEOUT_AFTER_PROCESSING`.

To test the lost-after-processing response use `TIMEOUT_AFTER_PROCESSING` and query the same ID afterward. Do not interpret a timeout as definitive failure.

## Tests

```powershell
cd settlement-simulator
mvn clean verify
```

Requires Docker running for Testcontainers. Covers successful replay, amount mismatch, rejection, delayed settlement, both timeout types, concurrent identical requests, and validation. Tests are also run in `.github/workflows/ci.yml` after push.

## Security/deployment constraints

The simulator intentionally accepts deterministic fault modes without authentication for **local-only testing**. Do not expose its port publicly or deploy it as-is. Later milestones should keep internal endpoints network-restricted and use authentication for any remotely accessible control surface. Do not point real payment data at this service.
