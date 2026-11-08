package com.payrail.simulator;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class SimulatorExceptionHandler {
    @ExceptionHandler(SettlementConflictException.class)
    ProblemDetail conflict(SettlementConflictException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Settlement idempotency conflict");
        return problem;
    }

    @ExceptionHandler(SettlementNotFoundException.class)
    ProblemDetail notFound(SettlementNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Settlement not found");
        return problem;
    }
}
