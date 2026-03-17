package dev.mars.peegeeq.cache.runtime.bootstrap;

import dev.mars.peegeeq.cache.pg.config.PgCacheStoreConfig;
import dev.mars.peegeeq.cache.runtime.config.PeeGeeCacheConfig;
import io.vertx.pgclient.PgConnectOptions;

/**
 * Options for bootstrapping a {@link dev.mars.peegeeq.cache.runtime.PeeGeeCacheManager}.
 *
 * @param runtimeConfig   runtime behavior configuration
 * @param storeConfig     PostgreSQL-specific store configuration
 * @param connectOptions  connection options for the dedicated pub/sub listener connection (nullable)
 */
public record PeeGeeCacheBootstrapOptions(
        PeeGeeCacheConfig runtimeConfig,
        PgCacheStoreConfig storeConfig,
        PgConnectOptions connectOptions
) {

    /** Convenience constructor without explicit connect options. */
    public PeeGeeCacheBootstrapOptions(PeeGeeCacheConfig runtimeConfig, PgCacheStoreConfig storeConfig) {
        this(runtimeConfig, storeConfig, null);
    }

    public static PeeGeeCacheBootstrapOptions defaults() {
        return new PeeGeeCacheBootstrapOptions(
                PeeGeeCacheConfig.defaults(),
                PgCacheStoreConfig.defaults(),
                null
        );
    }
}
