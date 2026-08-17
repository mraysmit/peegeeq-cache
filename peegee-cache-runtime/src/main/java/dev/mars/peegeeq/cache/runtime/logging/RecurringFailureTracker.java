package dev.mars.peegeeq.cache.runtime.logging;

import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;

/** Tracks a recurring failure episode so logs can report transitions instead of every occurrence. */
public final class RecurringFailureTracker {

    private final AtomicLong failures = new AtomicLong();

    /** Records a failure and identifies whether it begins a new failure episode. */
    public Failure recordFailure() {
        long failureCount = failures.incrementAndGet();
        return new Failure(failureCount == 1, failureCount - 1);
    }

    /**
     * Records successful recovery.
     *
     * @return the number of repeated failure logs suppressed during the episode, or empty when healthy already
     */
    public OptionalLong recordRecovery() {
        long failureCount = failures.getAndSet(0);
        if (failureCount == 0) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(failureCount - 1);
    }

    public record Failure(boolean firstFailure, long repeatedFailures) {
    }
}
