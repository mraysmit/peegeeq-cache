package dev.mars.peegeeq.cache.benchmark;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BenchmarkPersistencePolicyTest {
    @Test
    void acceptsFiniteCampaignOnlyWhenFinalTemporaryAndRewriteBudgetsFit() {
        var policy = new BenchmarkPersistencePolicy(1, 10, 1_000_000, 20_000_000, 3_000_000);
        var accepted = policy.assess(100, 1_000, 20_000);
        assertTrue(accepted.supported());
        assertEquals(10, accepted.checkpoints());
        assertEquals(120_000, accepted.estimatedFinalBytes());
        assertEquals(770_000, accepted.estimatedCumulativeWriteBytes());

        var rejected = policy.assess(10_000, 1_000, 20_000);
        assertFalse(rejected.supported());
        assertTrue(rejected.reasons().stream().anyMatch(reason -> reason.contains("final evidence")));
        assertTrue(rejected.reasons().stream().anyMatch(reason -> reason.contains("rewrite")));
        assertTrue(rejected.reasons().stream().anyMatch(reason -> reason.contains("temporary disk")));
    }

    @Test
    void rejectsOverflowRatherThanWrappingAPlanningEstimate() {
        var policy = new BenchmarkPersistencePolicy(1, 1, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
        assertFalse(policy.assess(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE).supported());
    }
}
