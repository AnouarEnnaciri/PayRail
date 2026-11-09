# M12 - apply only to the verified M11 baseline. All new files are copied by Expand-Archive.
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$head = (git rev-parse --short HEAD).Trim()
if ($head -ne '2eb08ea') { throw "M12 requires Git HEAD 2eb08ea (found $head)." }

function Replace-Strict([string]$relative, [string]$before, [string]$after) {
    $path = Join-Path $PSScriptRoot $relative
    if (-not (Test-Path -LiteralPath $path)) { throw "Missing: $relative" }
    $source = [System.IO.File]::ReadAllText($path).Replace("`r`n", "`n")
    if (-not $source.Contains($before)) { throw "Expected M11 code not found in $relative" }
    $first = $source.IndexOf($before)
    if ($source.IndexOf($before, $first + $before.Length) -ge 0) { throw "Ambiguous replacement in $relative" }
    $result = $source.Replace($before, $after)
    [System.IO.File]::WriteAllText($path, $result, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host "Updated: $relative"
}

$security = 'backend/src/main/java/com/payrail/backend/security/SecurityConfig.java'
Replace-Strict $security @'
        http
                .authorizeHttpRequests(auth -> auth
'@ @'
        http
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/settlement/callbacks"))
                .authorizeHttpRequests(auth -> auth
'@
Replace-Strict $security @'
                        .requestMatchers("/api/ops/**").hasRole("OPS")
'@ @'
                        .requestMatchers("/api/settlement/callbacks").permitAll()
                        .requestMatchers("/api/ops/**").hasRole("OPS")
'@

$mode = 'settlement-simulator/src/main/java/com/payrail/simulator/SettlementMode.java'
Replace-Strict $mode @'
    TIMEOUT_AFTER_PROCESSING
'@ @'
    TIMEOUT_AFTER_PROCESSING,
    DUPLICATE_CALLBACK
'@

$service = 'settlement-simulator/src/main/java/com/payrail/simulator/SettlementService.java'
Replace-Strict $service @'
            case SUCCESS, TIMEOUT_AFTER_PROCESSING -> SettlementStatus.SETTLED;
'@ @'
            case SUCCESS, TIMEOUT_AFTER_PROCESSING, DUPLICATE_CALLBACK -> SettlementStatus.SETTLED;
'@

$controller = 'settlement-simulator/src/main/java/com/payrail/simulator/SettlementController.java'
Replace-Strict $controller @'
    private final SettlementService service;
    private final long timeoutDelayMs;
'@ @'
    private final SettlementService service;
    private final SettlementCallbackPublisher callbackPublisher;
    private final long timeoutDelayMs;
'@
Replace-Strict $controller @'
    SettlementController(SettlementService service,
                         @Value("${simulator.timeout-delay-ms}") long timeoutDelayMs) {
'@ @'
    SettlementController(SettlementService service, SettlementCallbackPublisher callbackPublisher,
                         @Value("${simulator.timeout-delay-ms}") long timeoutDelayMs) {
'@
Replace-Strict $controller @'
        this.service = service;
        this.timeoutDelayMs = timeoutDelayMs;
'@ @'
        this.service = service;
        this.callbackPublisher = callbackPublisher;
        this.timeoutDelayMs = timeoutDelayMs;
'@
Replace-Strict $controller @'
        SettlementService.Submission submission = service.submit(request);
'@ @'
        SettlementService.Submission submission = service.submit(request);
        // The simulator DB transaction has committed before these HTTP notifications.
        if (request.mode() == SettlementMode.DUPLICATE_CALLBACK) {
            callbackPublisher.publishTwice(submission.response());
        }
'@

$outbox = 'backend/src/main/java/com/payrail/backend/outbox/OutboxStore.java'
Replace-Strict $outbox @'
    @Transactional
    public void complete(Claim claim, Completion completion) {
        int recorded = jdbc.update("""
'@ @'
    @Transactional
    public void complete(Claim claim, Completion completion) {
        // Use the same lock order as callbacks: outbox -> transfer.
        String current = jdbc.queryForObject(
                "SELECT status FROM outbox_events WHERE id = ? FOR UPDATE",
                String.class, claim.eventId());
        if ("PROCESSED".equals(current)) {
            // Only callback completion may supersede a still-in-flight worker.
            Boolean viaCallback = jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM settlement_callbacks
                                   WHERE transfer_id = ? AND disposition = 'FINALIZED')
                    """, Boolean.class, claim.transferId());
            if (!Boolean.TRUE.equals(viaCallback)) {
                throw new IllegalStateException("Outbox already processed by another worker");
            }
            Transfer finalized = transfers.findByIdForUpdate(claim.transferId())
                    .orElseThrow(() -> new IllegalStateException("Finalized transfer missing"));
            if (completion.terminalReceipt()) {
                String expected = "SETTLED".equals(completion.outcome()) ? "SETTLED"
                        : "REJECTED".equals(completion.outcome()) ? "FAILED" : null;
                if (expected == null || !expected.equals(finalized.getStatus())) {
                    throw new IllegalStateException("Worker receipt contradicts prior callback");
                }
            }
            int audited = jdbc.update("""
                    UPDATE settlement_attempts
                    SET completed_at = now(), outcome = ?, http_status = ?, error_category = ?,
                        external_reference = ?, last_error = ?, latency_ms = ?
                    WHERE id = ? AND outcome = 'IN_FLIGHT'
                    """, completion.outcome(), completion.httpStatus(), completion.errorCategory(),
                    completion.externalReference(), abbreviate(completion.errorDetail()),
                    completion.latencyMs(), claim.attemptId());
            if (audited != 1) throw new IllegalStateException("Attempt already completed or missing");
            return; // No second terminal ledger effect.
        }

        int recorded = jdbc.update("""
'@

Write-Host 'M12 source patch applied. Run git diff --check and targeted Maven tests.'
