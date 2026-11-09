# Adjust M8 assertions for the intentional M10 escalation semantics.
# Run once from repository root AFTER expanding PayRail-M10.zip.
$ErrorActionPreference = 'Stop'
$path = Join-Path (Get-Location) 'backend/src/test/java/com/payrail/backend/OutboxWorkerIntegrationTest.java'
if (-not (Test-Path $path)) { throw "Missing test: $path" }

$raw = [System.IO.File]::ReadAllText($path)
$hasCRLF = $raw.Contains("`r`n")
$content = $raw.Replace("`r`n", "`n")

function Replace-Exactly([string]$before, [string]$after, [int]$expected) {
    $escaped = [regex]::Escape($before)
    $found = ([regex]::Matches($script:content, $escaped)).Count
    if ($found -ne $expected) {
        throw "M8 test patch conflict: expected $expected occurrence(s), found $found. No file written."
    }
    $script:content = $script:content.Replace($before, $after)
}

$old = @'
        when(transport.submit(any()))
                .thenThrow(new ResourceAccessException("lost response"))
                .thenAnswer(call -> receipt(call.getArgument(0), "SETTLED"));
'@
$new = $old + "`n" + '        when(transport.get(any())).thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));'
Replace-Exactly $old $new 1
$oldExhaustion = '        when(transport.submit(any())).thenThrow(new ResourceAccessException("timeout"));'
$newExhaustion = $oldExhaustion + "`n" + '        when(transport.get(any())).thenThrow(new ResourceAccessException("status lookup timeout"));'
Replace-Exactly $oldExhaustion $newExhaustion 1
Replace-Exactly 'assertEquals("NETWORK_ERROR", jdbc.queryForObject(' 'assertEquals("NOT_FOUND", jdbc.queryForObject(' 1
Replace-Exactly 'assertEquals("PENDING_SETTLEMENT", jdbc.queryForObject(' 'assertEquals("RECONCILIATION_REQUIRED", jdbc.queryForObject(' 2
Replace-Exactly 'void exhaustedRetriesDeadLetterOnlyTheJobNotTheFinancialTransfer()' 'void exhaustedRetriesEscalateFinancialTransferWithoutRefund()' 1

if ($hasCRLF) { $content = $content.Replace("`n", "`r`n") }
[System.IO.File]::WriteAllText($path, $content, [System.Text.UTF8Encoding]::new($false))
Write-Host 'M8 expectations updated for M10 (NOT_FOUND / RECONCILIATION_REQUIRED).'
