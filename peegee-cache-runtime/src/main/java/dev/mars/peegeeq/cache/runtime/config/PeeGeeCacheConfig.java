package dev.mars.peegeeq.cache.runtime.config;

import java.time.Duration;

/**
 * Runtime configuration for peegee-cache behavior.
 *
 * @param defaultTtl            default TTL for entries that do not specify one (null = persistent)
 * @param expirySweepInterval   interval between expiry sweeper runs
 * @param expirySweepBatchSize  max rows deleted per sweeper pass
 * @param enableExpirySweeper   whether to start the background expiry sweeper
 * @param writeBehind           optional cache write-behind configuration
 */
public record PeeGeeCacheConfig(
        Duration defaultTtl,
        Duration expirySweepInterval,
        int expirySweepBatchSize,
        boolean enableExpirySweeper,
        WriteBehindConfig writeBehind
) {

    public PeeGeeCacheConfig {
        writeBehind = writeBehind != null ? writeBehind : WriteBehindConfig.disabled();
        if (defaultTtl != null && (defaultTtl.isZero() || defaultTtl.isNegative())) {
            throw new IllegalArgumentException("defaultTtl must be > 0 when configured");
        }
        if (enableExpirySweeper) {
            if (expirySweepInterval == null || expirySweepInterval.isZero() || expirySweepInterval.isNegative()) {
                throw new IllegalArgumentException("expirySweepInterval must be > 0 when enableExpirySweeper=true");
            }
            if (expirySweepBatchSize <= 0) {
                throw new IllegalArgumentException("expirySweepBatchSize must be > 0 when enableExpirySweeper=true");
            }
        }
        if (writeBehind.enabled() && defaultTtl != null
                && writeBehind.flushInterval().compareTo(defaultTtl) > 0) {
            throw new IllegalArgumentException("writeBehind.flushInterval must be <= defaultTtl when configured");
        }
    }

    public PeeGeeCacheConfig(
            Duration defaultTtl,
            Duration expirySweepInterval,
            int expirySweepBatchSize,
            boolean enableExpirySweeper) {
        this(defaultTtl, expirySweepInterval, expirySweepBatchSize,
                enableExpirySweeper, WriteBehindConfig.disabled());
    }

    public static PeeGeeCacheConfig defaults() {
        return new PeeGeeCacheConfig(
                null,
                Duration.ofSeconds(30),
                500,
                false,
                WriteBehindConfig.disabled()
        );
    }
}
