package dev.mars.peegeeq.cache.runtime.bootstrap;

import dev.mars.peegeeq.cache.api.model.PublishRequest;
import dev.mars.peegeeq.cache.api.model.ScanRequest;
import dev.mars.peegeeq.cache.runtime.config.PeeGeeCacheConfig;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
class PeeGeeCachesLifecycleTest {

    private Pool pool;

    @BeforeEach
    void setUp(Vertx vertx) {
        PgConnectOptions connectOptions = new PgConnectOptions()
                .setHost("localhost")
                .setPort(1)
                .setDatabase("postgres")
                .setUser("postgres")
                .setPassword("postgres");
        pool = Pool.pool(vertx, connectOptions, new PoolOptions().setMaxSize(1));
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        if (pool == null) {
            ctx.completeNow();
            return;
        }
        pool.close().onComplete(ctx.succeeding(v -> ctx.completeNow()));
    }

    @Test
    void createProvidesManagerInStoppedState(Vertx vertx, VertxTestContext ctx) {
        PeeGeeCaches.create(vertx, pool)
                .onComplete(ctx.succeeding(manager -> ctx.verify(() -> {
                    assertFalse(manager.isStarted());
                    ctx.completeNow();
                })));
    }

    @Test
    void cacheBeforeStartThrows(Vertx vertx, VertxTestContext ctx) {
        PeeGeeCaches.create(vertx, pool)
                .onComplete(ctx.succeeding(manager -> ctx.verify(() -> {
                    IllegalStateException ex = assertThrows(IllegalStateException.class, manager::cache);
                    assertTrue(ex.getMessage().contains("not started"));
                    ctx.completeNow();
                })));
    }

    @Test
    void startThenStopTransitionsState(Vertx vertx, VertxTestContext ctx) {
        PeeGeeCaches.create(vertx, pool)
                .compose(manager -> manager.startReactive()
                        .map(manager)
                )
                .compose(manager -> {
                    ctx.verify(() -> {
                        assertTrue(manager.isStarted());
                        assertNotNull(manager.cache());
                    });
                    return manager.stopReactive().map(manager);
                })
                .onComplete(ctx.succeeding(manager -> ctx.verify(() -> {
                    assertFalse(manager.isStarted());
                    ctx.completeNow();
                })));
    }

    @Test
    void secondStartFails(Vertx vertx, VertxTestContext ctx) {
        PeeGeeCaches.create(vertx, pool)
            .onComplete(ctx.succeeding(manager ->
                manager.startReactive().onComplete(ctx.succeeding(v1 ->
                    manager.startReactive().onComplete(ctx.failing(err ->
                        manager.stopReactive().onComplete(ctx.succeeding(v2 ->
                            ctx.verify(() -> {
                                assertInstanceOf(IllegalStateException.class, err);
                                assertTrue(err.getMessage().contains("already started"));
                                ctx.completeNow();
                            })
                        ))
                    ))
                ))
            ));
    }

    @Test
    void stopBeforeStartFails(Vertx vertx, VertxTestContext ctx) {
        PeeGeeCaches.create(vertx, pool)
            .onComplete(ctx.succeeding(manager ->
                manager.stopReactive().onComplete(ctx.failing(err ->
                    ctx.verify(() -> {
                        assertInstanceOf(IllegalStateException.class, err);
                        assertTrue(err.getMessage().contains("not started"));
                        ctx.completeNow();
                    })
                ))
            ));
    }

    @Test
    void nullOptionsAreAcceptedAndNormalized(Vertx vertx, VertxTestContext ctx) {
        PeeGeeCaches.create(vertx, pool, null)
                .compose(manager -> manager.startReactive().map(manager))
                .compose(manager -> manager.stopReactive().map(manager))
                .onComplete(ctx.succeeding(manager -> ctx.verify(ctx::completeNow)));
    }

    @Test
    void explicitOptionsAreAccepted(Vertx vertx, VertxTestContext ctx) {
        PeeGeeCacheBootstrapOptions options = new PeeGeeCacheBootstrapOptions(
            new PeeGeeCacheConfig(Duration.ofSeconds(45), Duration.ofSeconds(10), 250, true),
            new dev.mars.peegeeq.cache.pg.config.PgCacheStoreConfig("custom_schema", "custom_prefix")
        );

        PeeGeeCaches.create(vertx, pool, options)
            .compose(manager -> manager.startReactive().map(manager))
            .compose(manager -> manager.stopReactive().map(manager))
            .onComplete(ctx.succeeding(manager -> ctx.verify(ctx::completeNow)));
    }

    @Test
    void partialNullConfigsAreNormalized(Vertx vertx, VertxTestContext ctx) {
        PeeGeeCacheBootstrapOptions onlyRuntime = new PeeGeeCacheBootstrapOptions(PeeGeeCacheConfig.defaults(), null);
        PeeGeeCacheBootstrapOptions onlyStore = new PeeGeeCacheBootstrapOptions(
            null,
            new dev.mars.peegeeq.cache.pg.config.PgCacheStoreConfig("peegee_cache", "peegee_cache")
        );

        PeeGeeCaches.create(vertx, pool, onlyRuntime)
            .compose(manager -> manager.startReactive().map(manager))
            .compose(manager -> manager.stopReactive())
            .compose(v -> PeeGeeCaches.create(vertx, pool, onlyStore))
            .compose(manager -> manager.startReactive().map(manager))
            .compose(manager -> manager.stopReactive())
            .onComplete(ctx.succeeding(v -> ctx.completeNow()));
    }

    @Test
    void scanAndPubSubStubsFailFastWithUnsupportedOperation(Vertx vertx, VertxTestContext ctx) {
        PeeGeeCaches.create(vertx, pool)
                .onComplete(ctx.succeeding(manager ->
                        manager.startReactive().onComplete(ctx.succeeding(v1 ->
                                manager.cache().scan().scan(new ScanRequest("ns", "p", null, 10, false, false))
                                        .onComplete(ctx.failing(scanErr -> {
                                            ctx.verify(() -> {
                                                assertInstanceOf(UnsupportedOperationException.class, scanErr);
                                                assertTrue(scanErr.getMessage().contains("scan"));
                                            });
                                            manager.cache().pubSub().publish(new PublishRequest("ch", "payload", "text/plain"))
                                                    .onComplete(ctx.failing(pubErr -> {
                                                        ctx.verify(() -> {
                                                            assertInstanceOf(UnsupportedOperationException.class, pubErr);
                                                            assertTrue(pubErr.getMessage().contains("publish"));
                                                        });
                                                        manager.stopReactive().onComplete(ctx.succeeding(v2 -> ctx.completeNow()));
                                                    }));
                                        }))
                        ))
                ));
    }

    @Test
    void createRejectsNullVertxAndNullPool(Vertx vertx, VertxTestContext ctx) {
        assertThrows(NullPointerException.class, () -> PeeGeeCaches.create(null, pool));
        assertThrows(NullPointerException.class, () -> PeeGeeCaches.create(vertx, null));
        ctx.completeNow();
    }

    @Test
    void closeStopsWhenStartedAndIsNoopWhenStopped(Vertx vertx, VertxTestContext ctx) {
        PeeGeeCaches.create(vertx, pool)
                .onComplete(ctx.succeeding(manager ->
                        manager.startReactive().onComplete(ctx.succeeding(v -> {
                            manager.close();
                            ctx.verify(() -> assertFalse(manager.isStarted()));
                            manager.close();
                            ctx.completeNow();
                        }))
                ));
    }

    @Test
    void startOwnsAndStopsBackgroundLifecycles(Vertx vertx, VertxTestContext ctx) {
        PeeGeeCacheBootstrapOptions options = new PeeGeeCacheBootstrapOptions(
                new PeeGeeCacheConfig(null, Duration.ofMillis(25), 32, true),
                dev.mars.peegeeq.cache.pg.config.PgCacheStoreConfig.defaults()
        );

        PeeGeeCaches.create(vertx, pool, options)
                .compose(manager -> manager.startReactive().map(manager))
                .compose(manager -> {
                    ctx.verify(() -> {
                        PgPeeGeeCacheManager pgManager = (PgPeeGeeCacheManager) manager;
                        assertTrue(pgManager.isExpirySweeperRunning());
                        assertTrue(pgManager.isListenerRunning());
                    });
                    return manager.stopReactive().map(manager);
                })
                .onComplete(ctx.succeeding(manager -> ctx.verify(() -> {
                    PgPeeGeeCacheManager pgManager = (PgPeeGeeCacheManager) manager;
                    assertFalse(pgManager.isExpirySweeperRunning());
                    assertFalse(pgManager.isListenerRunning());
                    ctx.completeNow();
                })));
    }

    @Test
    void invalidSweeperConfigFailsAtConstruction(Vertx vertx, VertxTestContext ctx) {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new PeeGeeCacheConfig(null, Duration.ZERO, 0, true));
        assertTrue(ex.getMessage().contains("expirySweepInterval must be > 0"));
        ctx.completeNow();
    }

    @Test
    void managerExposesBoundVertxAndPool(Vertx vertx, VertxTestContext ctx) {
        PeeGeeCaches.create(vertx, pool)
                .compose(manager -> manager.startReactive().map(manager))
                .compose(manager -> {
                    ctx.verify(() -> {
                        assertSame(vertx, manager.vertx());
                        assertSame(pool, manager.pool());
                    });
                    return manager.stopReactive();
                })
                .onComplete(ctx.succeeding(v -> ctx.completeNow()));
    }

    @Test
    void cacheAfterStopThrows(Vertx vertx, VertxTestContext ctx) {
        PeeGeeCaches.create(vertx, pool)
                .compose(manager -> manager.startReactive().map(manager))
                .compose(manager -> manager.stopReactive().map(manager))
                .onComplete(ctx.succeeding(manager -> ctx.verify(() -> {
                    IllegalStateException ex = assertThrows(IllegalStateException.class, manager::cache);
                    assertTrue(ex.getMessage().contains("not started"));
                    ctx.completeNow();
                })));
    }
}
