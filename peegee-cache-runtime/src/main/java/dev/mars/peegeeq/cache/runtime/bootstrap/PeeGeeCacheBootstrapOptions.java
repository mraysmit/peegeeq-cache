package dev.mars.peegeeq.cache.runtime.bootstrap;

import dev.mars.peegeeq.cache.pg.config.PgCacheStoreConfig;
import dev.mars.peegeeq.cache.runtime.config.PeeGeeCacheConfig;
import dev.mars.peegeeq.cache.core.telemetry.CacheTelemetry;
import io.vertx.pgclient.PgConnectOptions;

/**
 * Options for bootstrapping a {@link dev.mars.peegeeq.cache.runtime.PeeGeeCacheManager}.
 *
 * @param runtimeConfig   runtime behavior configuration
 * @param storeConfig     PostgreSQL-specific store configuration
 * @param connectOptions  connection options for the dedicated pub/sub listener connection (nullable)
 * @param telemetry       production telemetry exporter (never null after normalization)
 * @param schemaBootstrapMode schema ownership policy
 */
public record PeeGeeCacheBootstrapOptions(
        PeeGeeCacheConfig runtimeConfig,
        PgCacheStoreConfig storeConfig,
        PgConnectOptions connectOptions,
        CacheTelemetry telemetry,
        SchemaBootstrapMode schemaBootstrapMode
) {

    public PeeGeeCacheBootstrapOptions(PeeGeeCacheConfig runtimeConfig, PgCacheStoreConfig storeConfig,
                                       PgConnectOptions connectOptions, CacheTelemetry telemetry) {
        this(runtimeConfig, storeConfig, connectOptions, telemetry, SchemaBootstrapMode.EXTERNAL);
    }

    public PeeGeeCacheBootstrapOptions(PeeGeeCacheConfig runtimeConfig, PgCacheStoreConfig storeConfig,
                                       PgConnectOptions connectOptions) {
        this(runtimeConfig, storeConfig, connectOptions, CacheTelemetry.noop(), SchemaBootstrapMode.EXTERNAL);
    }

    /** Convenience constructor without explicit connect options. */
    public PeeGeeCacheBootstrapOptions(PeeGeeCacheConfig runtimeConfig, PgCacheStoreConfig storeConfig) {
        this(runtimeConfig, storeConfig, null, CacheTelemetry.noop(), SchemaBootstrapMode.EXTERNAL);
    }

    public static PeeGeeCacheBootstrapOptions defaults() {
        return new PeeGeeCacheBootstrapOptions(
                PeeGeeCacheConfig.defaults(),
                PgCacheStoreConfig.defaults(),
                null,
                CacheTelemetry.noop(),
                SchemaBootstrapMode.EXTERNAL
        );
    }
}
