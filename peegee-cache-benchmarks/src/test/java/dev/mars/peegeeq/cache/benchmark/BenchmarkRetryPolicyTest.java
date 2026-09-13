package dev.mars.peegeeq.cache.benchmark;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class BenchmarkRetryPolicyTest {
    @Test
    void retryBudgetAndDelayAreExplicitAndBounded() {
        var policy = new BenchmarkRetryPolicy(3, Duration.ofMillis(5));
        assertEquals(3, policy.maximumAttempts());
        assertEquals(Duration.ofMillis(5), policy.delay());
        assertTrue(policy.allowsRetryAfter(0));
        assertTrue(policy.allowsRetryAfter(1));
        assertFalse(policy.allowsRetryAfter(2));
        assertEquals(new BenchmarkRetryPolicy(1, Duration.ZERO), BenchmarkRetryPolicy.none());
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkRetryPolicy(0, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new BenchmarkRetryPolicy(2, Duration.ofNanos(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new BenchmarkRetryPolicy(2, Duration.ofMillis(-1)));
    }
}
