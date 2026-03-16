package dev.mars.peegeeq.cache.runtime.config;

import java.time.Duration;

/**
 * Runtime configuration for peegee-cache behavior.
 *
 * @param defaultTtl            default TTL for entries that do not specify one (null = persistent)
 * @param expirySweepInterval   interval between expiry sweeper runs
 * @param expirySweepBatchSize  max rows deleted per sweeper pass
 * @param enableExpirySweeper   whether to start the background expiry sweeper
 */
public record PeeGeeCacheConfig(
        Duration defaultTtl,
        Duration expirySweepInterval,
        int expirySweepBatchSize,
        boolean enableExpirySweeper
) {

    public static PeeGeeCacheConfig defaults() {
        return new PeeGeeCacheConfig(
                null,
                Duration.ofSeconds(30),
                500,
                false
        );
    }
}
