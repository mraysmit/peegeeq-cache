package dev.mars.peegeeq.cache.benchmark;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BenchmarkDeploymentTargetTest {
    @Test
    void externalTargetsAreExplicitAndNeverOwnTheServer() {
        var target = BenchmarkDeploymentTarget.external("staging", "db.example", 5432,
                "cache", "benchmark_campaign", true);
        assertEquals(BenchmarkDeploymentTarget.Kind.EXTERNAL, target.kind());
        assertFalse(target.serverOwned());
        assertTrue(target.tlsRequired());
        assertThrows(IllegalArgumentException.class,
                () -> BenchmarkDeploymentTarget.external("bad", "localhost", 5432,
                        "cache", "public", false));
    }

    @Test
    void localTargetOwnsOnlyItsDisposableContainerAndRunSchemaPrefix() {
        var target = BenchmarkDeploymentTarget.localTestcontainers("postgres:18.3-alpine", "benchmark_local");
        assertTrue(target.serverOwned());
        assertEquals(BenchmarkDeploymentTarget.Kind.LOCAL_TESTCONTAINERS, target.kind());
        assertEquals("postgres:18.3-alpine", target.image());
    }
}
