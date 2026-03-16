package dev.mars.peegeeq.cache.runtime.bootstrap;

import dev.mars.peegeeq.cache.runtime.PeeGeeCacheManager;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;

import java.util.Objects;

/**
 * Convenience bootstrap entrypoints for creating managed cache runtimes.
 */
public final class PeeGeeCaches {

    private static final PeeGeeCacheFactory FACTORY = new PgPeeGeeCacheFactory();

    private PeeGeeCaches() {}

    public static Future<PeeGeeCacheManager> create(Vertx vertx, Pool pool) {
        return create(vertx, pool, PeeGeeCacheBootstrapOptions.defaults());
    }

    public static Future<PeeGeeCacheManager> create(
            Vertx vertx,
            Pool pool,
            PeeGeeCacheBootstrapOptions options
    ) {
        Objects.requireNonNull(vertx, "vertx");
        Objects.requireNonNull(pool, "pool");
        return FACTORY.createManager(vertx, pool, options);
    }
}
