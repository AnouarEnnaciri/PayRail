package com.payrail.simulator;

import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/settlements")
class SettlementController {
    private final SettlementService service;
    private final long timeoutDelayMs;

    SettlementController(SettlementService service,
                         @Value("${simulator.timeout-delay-ms}") long timeoutDelayMs) {
        if (timeoutDelayMs < 0) {
            throw new IllegalArgumentException("simulator.timeout-delay-ms must be >= 0");
        }
        this.service = service;
        this.timeoutDelayMs = timeoutDelayMs;
    }

    @PostMapping
    ResponseEntity<SettlementResponse> submit(@Valid @RequestBody SubmitSettlementRequest request)
            throws InterruptedException {
        // The transactional service has returned: DB commit is already complete.
        SettlementService.Submission submission = service.submit(request);
        if (submission.noProcessing() || submission.loseResponse()) {
            // A real caller with a shorter read timeout observes network uncertainty.
            // Our HTTP 504 is only returned if the client keeps waiting.
            Thread.sleep(timeoutDelayMs);
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).build();
        }
        return ResponseEntity.ok(submission.response());
    }

    @GetMapping("/{id}")
    SettlementResponse get(@PathVariable UUID id) {
        return service.get(id);
    }
}
