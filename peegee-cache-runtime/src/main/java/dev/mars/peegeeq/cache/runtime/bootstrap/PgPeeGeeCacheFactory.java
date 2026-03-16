package dev.mars.peegeeq.cache.runtime.bootstrap;

import dev.mars.peegeeq.cache.runtime.PeeGeeCacheManager;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;

import java.util.Objects;

/**
 * PostgreSQL-backed implementation of {@link PeeGeeCacheFactory}.
 */
public final class PgPeeGeeCacheFactory implements PeeGeeCacheFactory {

    @Override
    public Future<PeeGeeCacheManager> createManager(Vertx vertx, Pool pool, PeeGeeCacheBootstrapOptions options) {
        Objects.requireNonNull(vertx, "vertx");
        Objects.requireNonNull(pool, "pool");

        PeeGeeCacheBootstrapOptions resolved = options != null
                ? options
                : PeeGeeCacheBootstrapOptions.defaults();

        return Future.succeededFuture(new PgPeeGeeCacheManager(vertx, pool, resolved));
    }
}
