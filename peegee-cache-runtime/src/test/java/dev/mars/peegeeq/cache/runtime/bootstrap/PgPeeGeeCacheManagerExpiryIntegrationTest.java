package dev.mars.peegeeq.cache.runtime.bootstrap;

import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.CacheSetRequest;
import dev.mars.peegeeq.cache.api.model.CacheValue;
import dev.mars.peegeeq.cache.api.model.SetMode;
import dev.mars.peegeeq.cache.api.model.TtlState;
import dev.mars.peegeeq.cache.core.telemetry.CacheOperation;
import dev.mars.peegeeq.cache.core.telemetry.CacheTelemetry;
import dev.mars.peegeeq.cache.pg.config.PgCacheStoreConfig;
import dev.mars.peegeeq.cache.runtime.PeeGeeCacheManager;
import dev.mars.peegeeq.cache.runtime.config.PeeGeeCacheConfig;
import dev.mars.peegeeq.cache.test.PgTestSupport;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
class PgPeeGeeCacheManagerExpiryIntegrationTest {

    private static final String SCHEMA = "expiry_runtime_schema";
    private static final PgTestSupport pg = new PgTestSupport("runtime-expiry-test", SCHEMA);

    private static Pool pool;

    @BeforeAll
    static void startPostgres(Vertx vertx, VertxTestContext ctx) throws Exception {
        pg.start(vertx);
        pool = pg.createPool(vertx);
        ctx.completeNow();
    }

    @BeforeEach
    void clearTables(VertxTestContext ctx) {
        pool.query("TRUNCATE TABLE " + SCHEMA + ".cache_entries, "
                        + SCHEMA + ".cache_counters, " + SCHEMA + ".cache_locks")
                .execute()
                .onComplete(ctx.succeeding(v -> ctx.completeNow()));
    }

    @AfterAll
    static void stopPostgres(VertxTestContext ctx) {
        if (pool == null) {
            pg.stop();
            ctx.completeNow();
            return;
        }
        pool.close().onComplete(ctx.succeeding(v -> {
            pg.stop();
            ctx.completeNow();
        }));
    }

    @Test
    void configuredDefaultTtlAppliesWhenRequestOmitsTtl(Vertx vertx, VertxTestContext ctx) {
        PeeGeeCacheConfig runtime = new PeeGeeCacheConfig(
                Duration.ofSeconds(5), Duration.ofSeconds(30), 100, false);
        PeeGeeCacheBootstrapOptions options = new PeeGeeCacheBootstrapOptions(
                runtime, new PgCacheStoreConfig(SCHEMA, SCHEMA));
        CacheKey key = new CacheKey("ttl", "defaulted");
        CacheSetRequest request = new CacheSetRequest(
                key, CacheValue.ofString("value"), null, SetMode.UPSERT, null, false);

        PeeGeeCaches.create(vertx, pool, options)
                .compose(manager -> manager.startReactive().map(manager))
                .compose(manager -> manager.cache().cache().set(request).map(manager))
                .compose(manager -> manager.cache().cache().ttl(key)
                        .compose(ttl -> {
                            ctx.verify(() -> {
                                assertEquals(TtlState.EXPIRING, ttl.state());
                                assertTrue(ttl.ttlMillis() > 0 && ttl.ttlMillis() <= 5_000);
                            });
                            return manager.stopReactive();
                        }))
                .onComplete(ctx.succeeding(v -> ctx.completeNow()));
    }

    @Test
    void sweeperPhysicallyDeletesExpiredRowsInBoundedPasses(Vertx vertx, VertxTestContext ctx) {
        String seedSql = """
                INSERT INTO %1$s.cache_entries
                    (namespace, cache_key, value_type, value_bytes, expires_at)
                SELECT 'sweep', 'entry-' || value, 'STRING', convert_to('v', 'UTF8'), NOW() - INTERVAL '1 second'
                FROM generate_series(1, 3) AS series(value);
                INSERT INTO %1$s.cache_counters
                    (namespace, counter_key, counter_value, expires_at)
                VALUES ('sweep', 'counter', 1, NOW() - INTERVAL '1 second');
                INSERT INTO %1$s.cache_locks
                    (namespace, lock_key, owner_token, updated_at, lease_expires_at)
                VALUES ('sweep', 'lock', 'owner', NOW() - INTERVAL '2 seconds', NOW() - INTERVAL '1 second');
                """.formatted(SCHEMA);

        PeeGeeCacheConfig runtime = new PeeGeeCacheConfig(
                null, Duration.ofMillis(25), 2, true);
        PeeGeeCacheBootstrapOptions options = new PeeGeeCacheBootstrapOptions(
                runtime, new PgCacheStoreConfig(SCHEMA, SCHEMA));

        pool.query(seedSql).execute()
                .compose(v -> PeeGeeCaches.create(vertx, pool, options))
                .compose(manager -> manager.startReactive().map(manager))
                .compose(manager -> awaitAllRowsDeleted(vertx, 100)
                        .compose(v -> manager.stopReactive()))
                .onComplete(ctx.succeeding(v -> ctx.completeNow()));
    }

    @Test
    void optInManagedBootstrapCreatesSchemaBeforeStartup(Vertx vertx, VertxTestContext ctx) {
        String managedSchema = "managed_bootstrap_schema";
        PeeGeeCacheBootstrapOptions options = new PeeGeeCacheBootstrapOptions(
                PeeGeeCacheConfig.defaults(),
                new PgCacheStoreConfig(managedSchema, managedSchema),
                null,
                dev.mars.peegeeq.cache.core.telemetry.CacheTelemetry.noop(),
                SchemaBootstrapMode.APPLY);
        CacheSetRequest request = new CacheSetRequest(
                new CacheKey("bootstrap", "created"), CacheValue.ofString("yes"), null,
                SetMode.UPSERT, null, false);

        pool.query("DROP SCHEMA IF EXISTS " + managedSchema + " CASCADE").execute()
                .compose(ignored -> PeeGeeCaches.create(vertx, pool, options))
                .compose(manager -> manager.startReactive().map(manager))
                .compose(manager -> manager.cache().cache().set(request)
                        .compose(result -> {
                            ctx.verify(() -> assertTrue(result.applied()));
                            return manager.stopReactive();
                        }))
                .onComplete(ctx.succeeding(ignored -> ctx.completeNow()));
    }

    @Test
    void injectedTelemetryObservesManagedLifecycleAndServiceOperations(Vertx vertx, VertxTestContext ctx) {
        RecordingTelemetry telemetry = new RecordingTelemetry();
        PeeGeeCacheBootstrapOptions options = new PeeGeeCacheBootstrapOptions(
                PeeGeeCacheConfig.defaults(), new PgCacheStoreConfig(SCHEMA, SCHEMA), null, telemetry);
        CacheSetRequest request = new CacheSetRequest(
                new CacheKey("telemetry", "set"), CacheValue.ofString("observed"), null,
                SetMode.UPSERT, null, false);

        PeeGeeCaches.create(vertx, pool, options)
                .compose(manager -> manager.startReactive().map(manager))
                .compose(manager -> manager.cache().cache().set(request).map(manager))
                .compose(manager -> manager.stopReactive())
                .onComplete(ctx.succeeding(ignored -> ctx.verify(() -> {
                    assertTrue(telemetry.completed.contains(CacheOperation.CACHE_SET));
                    assertEquals(java.util.List.of(true, false), telemetry.lifecycle);
                    ctx.completeNow();
                })));
    }

    private static Future<Void> awaitAllRowsDeleted(Vertx vertx, int attemptsRemaining) {
        String countSql = "SELECT "
                + "(SELECT COUNT(*) FROM " + SCHEMA + ".cache_entries) + "
                + "(SELECT COUNT(*) FROM " + SCHEMA + ".cache_counters) + "
                + "(SELECT COUNT(*) FROM " + SCHEMA + ".cache_locks) AS remaining";
        return pool.query(countSql).execute()
                .compose(rows -> {
                    long remaining = rows.iterator().next().getLong("remaining");
                    if (remaining == 0) {
                        return Future.succeededFuture();
                    }
                    if (attemptsRemaining == 0) {
                        return Future.failedFuture("Timed out waiting for expiry sweep; remaining=" + remaining);
                    }
                    return Future.future(promise -> vertx.setTimer(25, ignored -> promise.complete()))
                            .compose(v -> awaitAllRowsDeleted(vertx, attemptsRemaining - 1));
                });
    }

    private static final class RecordingTelemetry implements CacheTelemetry {
        private final CopyOnWriteArrayList<CacheOperation> completed = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<Boolean> lifecycle = new CopyOnWriteArrayList<>();

        @Override
        public OperationSpan startOperation(CacheOperation operation) {
            return failure -> {
                if (failure == null) {
                    completed.add(operation);
                }
            };
        }

        @Override
        public void recordLifecycle(boolean started) {
            lifecycle.add(started);
        }
    }
}
