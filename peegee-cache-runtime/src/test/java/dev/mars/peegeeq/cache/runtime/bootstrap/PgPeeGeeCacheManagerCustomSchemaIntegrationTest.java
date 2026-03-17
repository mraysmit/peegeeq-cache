package dev.mars.peegeeq.cache.runtime.bootstrap;

import dev.mars.peegeeq.cache.api.model.CacheEntry;
import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.CacheSetRequest;
import dev.mars.peegeeq.cache.api.model.CacheSetResult;
import dev.mars.peegeeq.cache.api.model.CacheValue;
import dev.mars.peegeeq.cache.api.model.LockAcquireRequest;
import dev.mars.peegeeq.cache.api.model.LockAcquireResult;
import dev.mars.peegeeq.cache.api.model.LockKey;
import dev.mars.peegeeq.cache.api.model.LockReleaseRequest;
import dev.mars.peegeeq.cache.api.model.SetMode;
import dev.mars.peegeeq.cache.pg.bootstrap.BootstrapSqlRenderer;
import dev.mars.peegeeq.cache.pg.config.PgCacheStoreConfig;
import dev.mars.peegeeq.cache.runtime.PeeGeeCacheManager;
import dev.mars.peegeeq.cache.runtime.config.PeeGeeCacheConfig;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
class PgPeeGeeCacheManagerCustomSchemaIntegrationTest {

    private static final String CUSTOM_SCHEMA_NAME = "runtime_custom_schema";
    private static final String POSTGRES_IMAGE = "postgres:18.3-alpine";
    private static final String DATABASE_NAME = "testdb";
    private static final String USERNAME = "test";
    private static final String PASSWORD = "test";

    @Test
    void managerUsesConfiguredNonDefaultSchemaAcrossCacheCounterAndLock(Vertx vertx) throws Exception {
        PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse(POSTGRES_IMAGE))
                .withDatabaseName(DATABASE_NAME)
                .withUsername(USERNAME)
                .withPassword(PASSWORD);

        Pool pool = null;
        PeeGeeCacheManager manager = null;
        try {
            postgres.start();
            applyBootstrapSql(vertx, postgres, CUSTOM_SCHEMA_NAME);

            pool = createPool(vertx, postgres);
            PeeGeeCacheBootstrapOptions options = new PeeGeeCacheBootstrapOptions(
                    PeeGeeCacheConfig.defaults(),
                    new PgCacheStoreConfig(CUSTOM_SCHEMA_NAME, CUSTOM_SCHEMA_NAME)
            );

            manager = await(PeeGeeCaches.create(vertx, pool, options), 10_000);
            await(manager.startReactive(), 10_000);

            CacheKey cacheKey = new CacheKey("ns", "k1");
            CacheSetRequest setRequest = new CacheSetRequest(
                    cacheKey,
                    CacheValue.ofString("value-1"),
                    null,
                    SetMode.UPSERT,
                    null,
                    false
            );

            CacheSetResult setResult = await(manager.cache().cache().set(setRequest), 10_000);
            assertTrue(setResult.applied());

            Optional<CacheEntry> entry = await(manager.cache().cache().get(cacheKey), 10_000);
            assertTrue(entry.isPresent());
            assertEquals("value-1", entry.get().value().asString());

            Long counterValue = await(manager.cache().counters().increment(new CacheKey("ns", "ctr")), 10_000);
            assertEquals(1L, counterValue);

            LockKey lockKey = new LockKey("ns", "lock-1");
            LockAcquireResult acquireResult = await(manager.cache().locks().acquire(
                    new LockAcquireRequest(lockKey, "owner-1", Duration.ofSeconds(5), false, true)
            ), 10_000);
            assertTrue(acquireResult.acquired());
            assertNotNull(acquireResult.fencingToken());

            Boolean released = await(manager.cache().locks().release(new LockReleaseRequest(lockKey, "owner-1")), 10_000);
            assertTrue(released);
        } finally {
            if (manager != null && manager.isStarted()) {
                await(manager.stopReactive(), 10_000);
            }
            if (pool != null) {
                await(pool.close(), 10_000);
            }
            postgres.stop();
        }
    }

    private static Pool createPool(Vertx vertx, PostgreSQLContainer<?> postgres) {
        PgConnectOptions connectOptions = new PgConnectOptions()
                .setHost(postgres.getHost())
                .setPort(postgres.getFirstMappedPort())
                .setDatabase(postgres.getDatabaseName())
                .setUser(postgres.getUsername())
                .setPassword(postgres.getPassword());
        return Pool.pool(vertx, connectOptions, new PoolOptions().setMaxSize(8));
    }

    private static void applyBootstrapSql(Vertx vertx, PostgreSQLContainer<?> postgres, String schemaName) throws Exception {
        String renderedSql = BootstrapSqlRenderer.loadForSchema(schemaName);

        PgConnectOptions connectOptions = new PgConnectOptions()
                .setHost(postgres.getHost())
                .setPort(postgres.getFirstMappedPort())
                .setDatabase(postgres.getDatabaseName())
                .setUser(postgres.getUsername())
                .setPassword(postgres.getPassword());

        Pool bootstrapPool = Pool.pool(vertx, connectOptions, new PoolOptions().setMaxSize(1));
        try {
            await(bootstrapPool.query(renderedSql).execute().mapEmpty(), 10_000);
        } finally {
            await(bootstrapPool.close(), 10_000);
        }
    }

    private static <T> T await(Future<T> future, long timeoutMillis) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> resultRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        future.onComplete(ar -> {
            if (ar.succeeded()) {
                resultRef.set(ar.result());
            } else {
                errorRef.set(ar.cause());
            }
            latch.countDown();
        });

        if (!latch.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
            throw new RuntimeException("Timed out waiting for Vert.x SQL operation");
        }

        Throwable error = errorRef.get();
        if (error != null) {
            throw new RuntimeException(error);
        }
        return resultRef.get();
    }
}
