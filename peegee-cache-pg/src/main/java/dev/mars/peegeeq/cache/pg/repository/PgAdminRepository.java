package dev.mars.peegeeq.cache.pg.repository;

import dev.mars.peegeeq.cache.api.model.EntryStats;
import dev.mars.peegeeq.cache.pg.sql.AdminSql;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;

import java.util.Objects;

/**
 * Repository for admin/stats queries against the peegee-cache schema.
 */
public final class PgAdminRepository {

    private final Pool pool;
    private final AdminSql sql;

    public PgAdminRepository(Pool pool, String schemaName) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.sql = AdminSql.forSchema(schemaName);
    }

    public Future<EntryStats> entryStats(String namespace) {
        Tuple params = Tuple.of(namespace);

        return pool.preparedQuery(sql.COUNT_LIVE_CACHE_ENTRIES).execute(params)
                .compose(cacheRows -> {
                    long cacheCount = cacheRows.iterator().next().getLong(0);
                    return pool.preparedQuery(sql.COUNT_LIVE_COUNTERS).execute(params)
                            .compose(counterRows -> {
                                long counterCount = counterRows.iterator().next().getLong(0);
                                return pool.preparedQuery(sql.COUNT_ACTIVE_LOCKS).execute(params)
                                        .map(lockRows -> {
                                            long lockCount = lockRows.iterator().next().getLong(0);
                                            return new EntryStats(namespace, cacheCount, counterCount, lockCount);
                                        });
                            });
                });
    }
}
