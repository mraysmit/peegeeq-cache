package dev.mars.peegeeq.cache.runtime.config;

import java.time.Duration;

/**
 * Configuration for optional asynchronous cache write buffering.
 *
 * @param enabled whether write-behind buffering is enabled
 * @param flushInterval interval between background flush attempts
 * @param maxBufferSize maximum number of distinct keys retained in the buffer
 * @param flushBatchSize maximum number of buffered writes processed per flush
 * @param maxRetries maximum retry count after a flush failure
 * @param shutdownDrainTimeout maximum time allowed for the shutdown drain
 */
public record WriteBehindConfig(
        boolean enabled,
        Duration flushInterval,
        int maxBufferSize,
        int flushBatchSize,
        int maxRetries,
        Duration shutdownDrainTimeout
) {

    public WriteBehindConfig {
        if (flushInterval == null || flushInterval.isZero() || flushInterval.isNegative()) {
            throw new IllegalArgumentException("flushInterval must be > 0");
        }
        if (maxBufferSize < 100) {
            throw new IllegalArgumentException("maxBufferSize must be >= 100");
        }
        if (flushBatchSize < 1 || flushBatchSize > maxBufferSize) {
            throw new IllegalArgumentException("flushBatchSize must be >= 1 and <= maxBufferSize");
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be >= 0");
        }
        if (shutdownDrainTimeout == null
                || shutdownDrainTimeout.isZero()
                || shutdownDrainTimeout.isNegative()) {
            throw new IllegalArgumentException("shutdownDrainTimeout must be > 0");
        }
    }

    public static WriteBehindConfig disabled() {
        return new WriteBehindConfig(
                false,
                Duration.ofMillis(500),
                10_000,
                500,
                3,
                Duration.ofSeconds(5));
    }
}
