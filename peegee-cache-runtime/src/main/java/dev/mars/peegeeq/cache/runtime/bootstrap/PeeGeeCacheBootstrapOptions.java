package dev.mars.peegeeq.cache.runtime.bootstrap;

import dev.mars.peegeeq.cache.pg.config.PgCacheStoreConfig;
import dev.mars.peegeeq.cache.runtime.config.PeeGeeCacheConfig;

/**
 * Options for bootstrapping a {@link dev.mars.peegeeq.cache.runtime.PeeGeeCacheManager}.
 *
 * @param runtimeConfig   runtime behavior configuration
 * @param storeConfig     PostgreSQL-specific store configuration
 */
public record PeeGeeCacheBootstrapOptions(
        PeeGeeCacheConfig runtimeConfig,
        PgCacheStoreConfig storeConfig
) {

    public static PeeGeeCacheBootstrapOptions defaults() {
        return new PeeGeeCacheBootstrapOptions(
                PeeGeeCacheConfig.defaults(),
                PgCacheStoreConfig.defaults()
        );
    }
}
