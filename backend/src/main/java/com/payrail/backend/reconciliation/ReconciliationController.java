package com.payrail.backend.reconciliation;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

/** SecurityConfig already restricts /api/ops/** to ROLE_OPS; POST keeps CSRF protection. */
@RestController
@RequestMapping("/api/ops/reconciliation")
public class ReconciliationController {
    private final ReconciliationService reconciliation;
    private final ReconciliationResolutionService resolution;

    public ReconciliationController(ReconciliationService reconciliation,
                                    ReconciliationResolutionService resolution) {
        this.reconciliation = reconciliation;
        this.resolution = resolution;
    }

    @PostMapping("/runs")
    @ResponseStatus(HttpStatus.CREATED)
    public ReconciliationService.RunView run(Principal principal) {
        return reconciliation.run(principal.getName());
    }

    @GetMapping("/runs/{runId}")
    public ReconciliationService.RunView get(@PathVariable UUID runId) {
        return reconciliation.getRun(runId);
    }

    @GetMapping("/runs/{runId}/issues")
    public List<ReconciliationService.IssueView> issues(@PathVariable UUID runId) {
        return reconciliation.issues(runId);
    }

    @PostMapping("/issues/{issueId}/resolve")
    public ReconciliationResolutionService.Resolution resolve(@PathVariable UUID issueId, Principal principal) {
        return resolution.resolve(issueId, principal.getName());
    }
}
