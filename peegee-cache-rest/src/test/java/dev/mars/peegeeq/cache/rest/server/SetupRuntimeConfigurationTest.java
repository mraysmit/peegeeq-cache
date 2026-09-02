package dev.mars.peegeeq.cache.rest.server;

import dev.mars.peegeeq.cache.runtime.bootstrap.SchemaBootstrapMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SetupRuntimeConfigurationTest {

    @Test
    void mapsEveryManagementSettingToTheRuntimeConfigurationWithoutLoss() {
        SetupRuntimeConfiguration configuration = new SetupRuntimeConfiguration(
                60_000L, true, 2_000, 75, true, 250, 2_000, 125, 8, 9_000,
                "managed_cache", false, SchemaBootstrapMode.APPLY,
                SetupRuntimeConfiguration.TelemetryMode.NOOP);

        var runtime = configuration.runtimeConfig();
        assertEquals(60_000, runtime.defaultTtl().toMillis());
        assertTrue(runtime.enableExpirySweeper());
        assertEquals(2_000, runtime.expirySweepInterval().toMillis());
        assertEquals(75, runtime.expirySweepBatchSize());
        assertTrue(runtime.writeBehind().enabled());
        assertEquals(250, runtime.writeBehind().flushInterval().toMillis());
        assertEquals(2_000, runtime.writeBehind().maxBufferSize());
        assertEquals(125, runtime.writeBehind().flushBatchSize());
        assertEquals(8, runtime.writeBehind().maxRetries());
        assertEquals(9_000, runtime.writeBehind().shutdownDrainTimeout().toMillis());

        SetupRuntimeSummary summary = SetupRuntimeSummary.from(configuration, 12);
        assertEquals("managed_cache", summary.pubSubChannelPrefix());
        assertFalse(summary.pubSubEnabled());
        assertEquals("APPLY", summary.schemaBootstrapMode());
        assertEquals("NOOP", summary.telemetryMode());
        assertEquals(12, summary.poolMaxSize());
    }

    @Test
    void defaultsMatchTheCoreRuntimeAndInvalidCrossFieldCombinationsAreRejected() {
        SetupRuntimeConfiguration defaults = SetupRuntimeConfiguration.defaults();
        assertNull(defaults.defaultTtlMillis());
        assertFalse(defaults.expirySweeperEnabled());
        assertFalse(defaults.writeBehindEnabled());
        assertTrue(defaults.pubSubEnabled());
        assertEquals(SchemaBootstrapMode.EXTERNAL, defaults.schemaBootstrapMode());

        assertThrows(IllegalArgumentException.class, () -> new SetupRuntimeConfiguration(
                100L, false, 1_000, 10, true, 101, 100, 10, 0, 1_000,
                "cache", true, SchemaBootstrapMode.EXTERNAL,
                SetupRuntimeConfiguration.TelemetryMode.NOOP));
        assertThrows(IllegalArgumentException.class, () -> new SetupRuntimeConfiguration(
                null, false, 1_000, 10, false, 100, 100, 101, 0, 1_000,
                "cache", true, SchemaBootstrapMode.EXTERNAL,
                SetupRuntimeConfiguration.TelemetryMode.NOOP));
    }
}
