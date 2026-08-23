package dev.mars.peegeeq.cache.rest.server;

public record SetupRuntimeSummary(
        Long defaultTtlMillis,
        boolean expirySweeperEnabled,
        long expirySweepIntervalMillis,
        int expirySweepBatchSize,
        int poolMaxSize) {

    public SetupRuntimeSummary {
        if (defaultTtlMillis != null && defaultTtlMillis < 1) {
            throw new IllegalArgumentException("defaultTtlMillis must be positive when configured");
        }
        if (expirySweepIntervalMillis < 1 || expirySweepBatchSize < 1 || poolMaxSize < 1) {
            throw new IllegalArgumentException("Runtime limits must be positive");
        }
    }
}
