package dev.mars.peegeeq.cache.rest.server;

import dev.mars.peegeeq.cache.runtime.bootstrap.SchemaBootstrapMode;
import dev.mars.peegeeq.cache.runtime.config.PeeGeeCacheConfig;
import dev.mars.peegeeq.cache.runtime.config.WriteBehindConfig;

import java.time.Duration;
import java.util.Objects;

/** Complete behavior configuration for a management-owned PeeGeeQ Cache runtime. */
public record SetupRuntimeConfiguration(
        Long defaultTtlMillis,
        boolean expirySweeperEnabled,
        long expirySweepIntervalMillis,
        int expirySweepBatchSize,
        boolean writeBehindEnabled,
        long writeBehindFlushIntervalMillis,
        int writeBehindMaxBufferSize,
        int writeBehindFlushBatchSize,
        int writeBehindMaxRetries,
        long writeBehindShutdownDrainTimeoutMillis,
        String pubSubChannelPrefix,
        boolean pubSubEnabled,
        SchemaBootstrapMode schemaBootstrapMode,
        TelemetryMode telemetryMode) {

    public SetupRuntimeConfiguration {
        if (defaultTtlMillis != null && defaultTtlMillis < 1) {
            throw new IllegalArgumentException("defaultTtlMillis must be positive when configured");
        }
        if (expirySweepIntervalMillis < 1 || expirySweepBatchSize < 1
                || writeBehindFlushIntervalMillis < 1 || writeBehindMaxBufferSize < 100
                || writeBehindFlushBatchSize < 1
                || writeBehindFlushBatchSize > writeBehindMaxBufferSize
                || writeBehindMaxRetries < 0 || writeBehindShutdownDrainTimeoutMillis < 1) {
            throw new IllegalArgumentException("Runtime configuration limits are invalid");
        }
        if (defaultTtlMillis != null && writeBehindEnabled
                && writeBehindFlushIntervalMillis > defaultTtlMillis) {
            throw new IllegalArgumentException(
                    "writeBehindFlushIntervalMillis must not exceed defaultTtlMillis");
        }
        pubSubChannelPrefix = requirePrefix(pubSubChannelPrefix);
        schemaBootstrapMode = Objects.requireNonNull(schemaBootstrapMode, "schemaBootstrapMode");
        telemetryMode = Objects.requireNonNull(telemetryMode, "telemetryMode");
    }

    public static SetupRuntimeConfiguration defaults() {
        PeeGeeCacheConfig runtime = PeeGeeCacheConfig.defaults();
        WriteBehindConfig writeBehind = runtime.writeBehind();
        return new SetupRuntimeConfiguration(
                null,
                runtime.enableExpirySweeper(),
                runtime.expirySweepInterval().toMillis(),
                runtime.expirySweepBatchSize(),
                writeBehind.enabled(),
                writeBehind.flushInterval().toMillis(),
                writeBehind.maxBufferSize(),
                writeBehind.flushBatchSize(),
                writeBehind.maxRetries(),
                writeBehind.shutdownDrainTimeout().toMillis(),
                "peegee_cache",
                true,
                SchemaBootstrapMode.EXTERNAL,
                TelemetryMode.NOOP);
    }

    public PeeGeeCacheConfig runtimeConfig() {
        return new PeeGeeCacheConfig(
                defaultTtlMillis == null ? null : Duration.ofMillis(defaultTtlMillis),
                Duration.ofMillis(expirySweepIntervalMillis),
                expirySweepBatchSize,
                expirySweeperEnabled,
                new WriteBehindConfig(
                        writeBehindEnabled,
                        Duration.ofMillis(writeBehindFlushIntervalMillis),
                        writeBehindMaxBufferSize,
                        writeBehindFlushBatchSize,
                        writeBehindMaxRetries,
                        Duration.ofMillis(writeBehindShutdownDrainTimeoutMillis)));
    }

    private static String requirePrefix(String value) {
        String prefix = Objects.requireNonNull(value, "pubSubChannelPrefix");
        int bytes = prefix.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (prefix.isBlank() || bytes > 48 || prefix.indexOf('\0') >= 0
                || prefix.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid pubSubChannelPrefix");
        }
        return prefix;
    }

    /** The core distribution currently ships only the vendor-neutral no-op exporter. */
    public enum TelemetryMode { NOOP }
}
