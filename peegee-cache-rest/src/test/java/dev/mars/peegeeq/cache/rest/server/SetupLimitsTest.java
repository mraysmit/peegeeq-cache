package dev.mars.peegeeq.cache.rest.server;

import dev.mars.peegeeq.cache.api.management.ManagementLimits;
import dev.mars.peegeeq.cache.runtime.bootstrap.SchemaBootstrapMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SetupLimitsTest {

    @Test
    void runtimePubSubPrefixDeterminesTheUsableChannelBytes() {
        SetupLimits limits = SetupLimits.from(configuration("browser_cache"), ManagementLimits.defaults());

        assertEquals(48, limits.pubSubChannelMaxBytes());
        assertEquals(7_500, limits.pubSubPayloadMaxBytes());
        assertEquals(10_485_760, limits.maximumValueBytes());
    }

    @Test
    void countsPrefixBytesNotCharacters() {
        SetupLimits limits = SetupLimits.from(configuration("café"), ManagementLimits.defaults());

        assertEquals(63 - 5 - 2, limits.pubSubChannelMaxBytes());
    }

    @Test
    void takesTheValueLimitFromServerConfiguration() {
        SetupLimits limits = SetupLimits.from(
                configuration("peegee_cache"), new ManagementLimits(75, 250, 4_096, 40, 3));

        assertEquals(49, limits.pubSubChannelMaxBytes());
        assertEquals(4_096, limits.maximumValueBytes());
    }

    @Test
    void rejectsValueLimitsBeyondTheApiIntegerRange() {
        ManagementLimits oversized = new ManagementLimits(75, 250, Integer.MAX_VALUE + 1L, 40, 3);

        assertThrows(IllegalArgumentException.class,
                () -> SetupLimits.from(configuration("peegee_cache"), oversized));
    }

    @Test
    void rejectsNonPositiveOrOversizedComponents() {
        assertThrows(IllegalArgumentException.class, () -> new SetupLimits(0, 7_500, 1));
        assertThrows(IllegalArgumentException.class, () -> new SetupLimits(64, 7_500, 1));
        assertThrows(IllegalArgumentException.class, () -> new SetupLimits(49, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new SetupLimits(49, 7_500, 0));
    }

    private static SetupRuntimeConfiguration configuration(String prefix) {
        return new SetupRuntimeConfiguration(
                60_000L, true, 30_000, 500,
                false, 50, 10_000, 1_000, 3, 30_000,
                prefix, true,
                SchemaBootstrapMode.APPLY,
                SetupRuntimeConfiguration.TelemetryMode.NOOP);
    }
}
