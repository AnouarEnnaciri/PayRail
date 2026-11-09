# M16 final read-only verification: no k6, container restarts, or volume deletion.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

Push-Location (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
try {
    $dc = @('compose', '--env-file', '.env', '-f', 'load-tests/compose.m16.yml')

    & docker @dc ps
    if ($LASTEXITCODE -ne 0) { throw 'M16 Docker Compose is unavailable' }

    foreach ($script in @('verify-storm.sql', 'verify-recovered.sql', 'verify-financial.sql', 'summary.sql')) {
        Write-Host "`nM16 SQL: $script"
        $cmd = 'psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f /verification/' + $script
        & docker @dc exec -T postgres sh -c $cmd
        if ($LASTEXITCODE -ne 0) { throw "SQL checkpoint failed: $script" }
    }

    # Pass the SQL through stdin instead of nested shell quotes on Windows PowerShell.
    # Compare the exact request IDs across the two independent PostgreSQL databases.
    $backendQuery = @'
SELECT settlement_request_id::text
FROM transfers
WHERE transfer_type = 'EXTERNAL'
  AND amount_minor = 19
  AND destination_reference LIKE 'LOAD-M16-%'
  AND status = 'SETTLED'
ORDER BY settlement_request_id;
'@
    $simulatorQuery = @'
SELECT settlement_request_id::text
FROM settlement_requests
WHERE amount_minor = 19
  AND destination_reference LIKE 'LOAD-M16-%'
  AND status = 'SETTLED'
ORDER BY settlement_request_id;
'@

    function Read-SettlementIds {
        param([string]$Service, [string]$Query)
        $psqlCommand = 'psql -X -A -t -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f -'
        $rows = @(($Query + "`n") | & docker @dc exec -T $Service sh -c $psqlCommand)
        if ($LASTEXITCODE -ne 0) { throw "Cannot read settlement IDs from $Service" }

        $ids = @($rows | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne '' })
        foreach ($id in $ids) {
            if ($id -notmatch '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$') {
                throw "Unexpected SQL output from $Service (expected UUID)"
            }
        }
        return $ids
    }

    $backendIds = @(Read-SettlementIds -Service 'postgres' -Query $backendQuery)
    $simulatorIds = @(Read-SettlementIds -Service 'simulator-db' -Query $simulatorQuery)

    Write-Host "Backend settled M16 transfers: $($backendIds.Count)"
    Write-Host "Simulator settled M16 requests: $($simulatorIds.Count)"

    if ($backendIds.Count -eq 0) { throw 'No M16 settled external transfers found in backend DB' }
    if (($backendIds -join ',') -ne ($simulatorIds -join ',')) {
        throw ('Settlement mismatch: backend={0}, simulator={1}' -f $backendIds.Count, $simulatorIds.Count)
    }

    Write-Host "`nPASS: $($backendIds.Count) settled request IDs match across both databases" -ForegroundColor Green
    Write-Host 'PASS: M16 final SQL checkpoint' -ForegroundColor Green
}
finally {
    Pop-Location
}
