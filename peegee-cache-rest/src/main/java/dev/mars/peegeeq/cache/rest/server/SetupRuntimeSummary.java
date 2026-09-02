package dev.mars.peegeeq.cache.rest.server;

public record SetupRuntimeSummary(
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
        String schemaBootstrapMode,
        String telemetryMode,
        int poolMaxSize) {

    public SetupRuntimeSummary {
        if (defaultTtlMillis != null && defaultTtlMillis < 1) {
            throw new IllegalArgumentException("defaultTtlMillis must be positive when configured");
        }
        if (expirySweepIntervalMillis < 1 || expirySweepBatchSize < 1
                || writeBehindFlushIntervalMillis < 1 || writeBehindMaxBufferSize < 100
                || writeBehindFlushBatchSize < 1
                || writeBehindFlushBatchSize > writeBehindMaxBufferSize
                || writeBehindMaxRetries < 0 || writeBehindShutdownDrainTimeoutMillis < 1
                || pubSubChannelPrefix == null || pubSubChannelPrefix.isBlank()
                || schemaBootstrapMode == null || telemetryMode == null || poolMaxSize < 1) {
            throw new IllegalArgumentException("Runtime limits must be positive");
        }
    }

    public static SetupRuntimeSummary from(
            SetupRuntimeConfiguration configuration, int poolMaxSize) {
        return new SetupRuntimeSummary(
                configuration.defaultTtlMillis(),
                configuration.expirySweeperEnabled(),
                configuration.expirySweepIntervalMillis(),
                configuration.expirySweepBatchSize(),
                configuration.writeBehindEnabled(),
                configuration.writeBehindFlushIntervalMillis(),
                configuration.writeBehindMaxBufferSize(),
                configuration.writeBehindFlushBatchSize(),
                configuration.writeBehindMaxRetries(),
                configuration.writeBehindShutdownDrainTimeoutMillis(),
                configuration.pubSubChannelPrefix(),
                configuration.pubSubEnabled(),
                configuration.schemaBootstrapMode().name(),
                configuration.telemetryMode().name(),
                poolMaxSize);
    }
}
