package com.payrail.backend.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Opt-in scheduler. Integration tests invoke OutboxProcessor deterministically. */
@Component
@ConditionalOnProperty(name = "payrail.outbox.worker.enabled", havingValue = "true")
public class OutboxScheduler {

    private final OutboxProcessor processor;

    public OutboxScheduler(OutboxProcessor processor) {
        this.processor = processor;
    }

    @Scheduled(fixedDelayString = "${payrail.outbox.worker.poll-ms:1000}")
    public void poll() {
        // Bounded batch: simple, avoids monopolizing the scheduler on busy demo databases.
        for (int i = 0; i < 10; i++) {
            if (!processor.processOne()) break;
        }
    }
}
