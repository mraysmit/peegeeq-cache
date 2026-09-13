package dev.mars.peegeeq.cache.benchmark;

import java.time.Duration;
import java.util.Objects;

/** A finite physical-attempt budget and fixed delay for one logical benchmark request. */
public record BenchmarkRetryPolicy(int maximumAttempts, Duration delay) {
    public BenchmarkRetryPolicy {
        if (maximumAttempts <= 0) throw new IllegalArgumentException("Maximum attempts must be positive");
        Objects.requireNonNull(delay, "delay");
        long nanos;
        try {
            nanos = delay.toNanos();
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Retry delay exceeds the nanosecond range", overflow);
        }
        if (nanos < 0 || nanos % 1_000_000 != 0) {
            throw new IllegalArgumentException("Retry delay must be non-negative whole milliseconds");
        }
    }

    public static BenchmarkRetryPolicy none() {
        return new BenchmarkRetryPolicy(1, Duration.ZERO);
    }

    public boolean allowsRetryAfter(int completedAttemptIndex) {
        if (completedAttemptIndex < 0) throw new IllegalArgumentException("Attempt index must not be negative");
        return completedAttemptIndex < maximumAttempts - 1;
    }
}
