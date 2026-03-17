package dev.mars.peegeeq.cache.pg.repository;

import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.CounterOptions;
import dev.mars.peegeeq.cache.api.model.CounterTtlMode;
import dev.mars.peegeeq.cache.api.model.TtlState;
import dev.mars.peegeeq.cache.pg.bootstrap.BootstrapSqlRenderer;
import dev.mars.peegeeq.cache.pg.test.PgTestSupport;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PgCounterRepositoryTest {

    private static final PgTestSupport pg = new PgTestSupport("pgcache-counter-test", "peegee_cache");
    private static Pool pool;
    private static PgCounterRepository repo;

    @BeforeAll
    static void startContainer(Vertx vertx) throws Exception {
        pg.start(vertx);
        pool = pg.createPool(vertx);
        repo = new PgCounterRepository(pool, "peegee_cache");
    }

    @AfterAll
    static void stopContainer() throws Exception {
        if (pool != null) pool.close();
        pg.stop();
    }

    // --- GET ---

    @Test
    @Order(1)
    void getReturnsEmptyForMissingCounter(VertxTestContext ctx) {
        CacheKey key = new CacheKey("ns", "no-counter");
        repo.get(key).onComplete(ctx.succeeding(result -> ctx.verify(() -> {
            assertTrue(result.isEmpty());
            ctx.completeNow();
        })));
    }

    @Test
    @Order(2)
    void getReturnsEmptyForExpiredCounter(VertxTestContext ctx) {
        CacheKey key = new CacheKey("ns", "expired-ctr");
        pool.query(
                        "INSERT INTO peegee_cache.cache_counters " +
                        "(namespace, counter_key, counter_value, expires_at) " +
                        "VALUES ('ns', 'expired-ctr', 42, NOW() - INTERVAL '1 hour') " +
                        "ON CONFLICT DO NOTHING")
                .execute()
                .compose(ignored -> repo.get(key))
                .onComplete(ctx.succeeding(result -> ctx.verify(() -> {
            assertTrue(result.isEmpty(), "Expired counter should be absent");
            ctx.completeNow();
        })));
    }

    // --- INCREMENT ---

    @Test
    @Order(10)
    void incrementCreatesCounterWithDelta(VertxTestContext ctx) {
        CacheKey key = new CacheKey("ns", "new-ctr");
        CounterOptions opts = CounterOptions.defaults();
        repo.increment(key, 1, opts).onComplete(ctx.succeeding(value -> ctx.verify(() -> {
            assertEquals(1L, value);
            ctx.completeNow();
        })));
    }

    @Test
    @Order(11)
    void incrementAddsToExisting(VertxTestContext ctx) {
        CacheKey key = new CacheKey("ns", "incr-ctr");
        CounterOptions opts = CounterOptions.defaults();
        repo.increment(key, 5, opts)
                .compose(v -> repo.increment(key, 3, opts))
                .onComplete(ctx.succeeding(value -> ctx.verify(() -> {
                    assertEquals(8L, value);
                    ctx.completeNow();
                })));
    }

    @Test
    @Order(12)
    void decrementSubtracts(VertxTestContext ctx) {
        CacheKey key = new CacheKey("ns", "decr-ctr");
        CounterOptions opts = CounterOptions.defaults();
        repo.increment(key, 10, opts)
                .compose(v -> repo.increment(key, -3, opts))
                .onComplete(ctx.succeeding(value -> ctx.verify(() -> {
                    assertEquals(7L, value);
                    ctx.completeNow();
                })));
    }

    @Test
    @Order(13)
    void incrementWithoutCreateReturnEmptyForMissing(VertxTestContext ctx) {
        CacheKey key = new CacheKey("ns", "no-create-ctr");
        CounterOptions opts = new CounterOptions(null, false, CounterTtlMode.PRESERVE_EXISTING);
        repo.increment(key, 1, opts).onComplete(ctx.succeeding(result -> ctx.verify(() -> {
            // When createIfMissing=false and counter doesn't exist, result should be null
            assertNull(result, "Increment without create should return null for missing counter");
            ctx.completeNow();
        })));
    }

    @Test
    @Order(14)
    void incrementRestartsAfterExpiry(VertxTestContext ctx) {
        CacheKey key = new CacheKey("ns", "restart-ctr");
        CounterOptions opts = CounterOptions.defaults();
        pool.query(
                        "INSERT INTO peegee_cache.cache_counters " +
                        "(namespace, counter_key, counter_value, expires_at) " +
                        "VALUES ('ns', 'restart-ctr', 100, NOW() - INTERVAL '1 hour') " +
                        "ON CONFLICT DO NOTHING")
                .execute()
                .compose(ignored -> repo.increment(key, 5, opts))
                .onComplete(ctx.succeeding(value -> ctx.verify(() -> {
            assertEquals(5L, value, "Counter should restart from delta after expiry");
            ctx.completeNow();
        })));
    }

    // --- TTL MODE ---

    @Test
    @Order(20)
    void ttlModePreserveExistingKeepsCurrentTtl(VertxTestContext ctx) {
        CacheKey key = new CacheKey("ns", "ttl-preserve");
        CounterOptions withTtl = new CounterOptions(Duration.ofHours(1), true, CounterTtlMode.PRESERVE_EXISTING);
        CounterOptions preserve = new CounterOptions(Duration.ofMinutes(1), true, CounterTtlMode.PRESERVE_EXISTING);

        // Create with 1h TTL, then increment with PRESERVE_EXISTING and 1m TTL — should keep 1h
        repo.increment(key, 1, withTtl)
                .compose(v -> repo.increment(key, 1, preserve))
                .compose(v -> repo.get(key))
                .onComplete(ctx.succeeding(result -> ctx.verify(() -> {
                    assertEquals(2L, result.orElseThrow());
                    ctx.completeNow();
                })));
    }

    @Test
    @Order(21)
    void ttlModeReplaceOverwritesTtl(VertxTestContext ctx) {
        CacheKey key = new CacheKey("ns", "ttl-replace");
        CounterOptions create = new CounterOptions(Duration.ofHours(1), true, CounterTtlMode.PRESERVE_EXISTING);
        CounterOptions replace = new CounterOptions(Duration.ofMinutes(5), true, CounterTtlMode.REPLACE);

        repo.increment(key, 1, create)
                .compose(v -> repo.increment(key, 1, replace))
                .compose(v -> repo.get(key))
                .onComplete(ctx.succeeding(result -> ctx.verify(() -> {
                    assertEquals(2L, result.orElseThrow());
                    ctx.completeNow();
                })));
    }

    @Test
    @Order(22)
    void ttlModeRemoveClearsTtl(VertxTestContext ctx) {
        CacheKey key = new CacheKey("ns", "ttl-remove");
        CounterOptions withTtl = new CounterOptions(Duration.ofHours(1), true, CounterTtlMode.PRESERVE_EXISTING);
        CounterOptions remove = new CounterOptions(null, true, CounterTtlMode.REMOVE);

        repo.increment(key, 1, withTtl)
                .compose(v -> repo.increment(key, 1, remove))
                .compose(v -> repo.get(key))
                .onComplete(ctx.succeeding(result -> ctx.verify(() -> {
                    assertEquals(2L, result.orElseThrow());
                    ctx.completeNow();
                })));
    }

    @Test
    @Order(23)
    void ttlReturnsMissingForUnknownCounter(VertxTestContext ctx) {
        CacheKey key = new CacheKey("ns", "ttl-missing");
        repo.ttl(key).onComplete(ctx.succeeding(ttl -> ctx.verify(() -> {
            assertEquals(TtlState.KEY_MISSING, ttl.state());
            assertNull(ttl.ttlMillis());
            ctx.completeNow();
        })));
    }

    @Test
    @Order(24)
    void expireAndPersistAffectCounterTtl(VertxTestContext ctx) {
        CacheKey key = new CacheKey("ns", "ttl-expire-persist");
        CounterOptions opts = CounterOptions.defaults();

        repo.increment(key, 1, opts)
                .compose(ignored -> repo.expire(key, Duration.ofMinutes(10)))
                .compose(updated -> {
                    assertTrue(updated);
                    return repo.ttl(key);
                })
                .compose(ttl -> {
                    assertEquals(TtlState.EXPIRING, ttl.state());
                    assertNotNull(ttl.ttlMillis());
                    assertTrue(ttl.ttlMillis() > 0L);
                    return repo.persist(key);
                })
                .compose(persisted -> {
                    assertTrue(persisted);
                    return repo.ttl(key);
                })
                .onComplete(ctx.succeeding(ttl -> ctx.verify(() -> {
                    assertEquals(TtlState.PERSISTENT, ttl.state());
                    assertNull(ttl.ttlMillis());
                    ctx.completeNow();
                })));
    }

    @Test
    @Order(25)
    void expireRejectsNonPositiveTtl(VertxTestContext ctx) {
        CacheKey key = new CacheKey("ns", "ttl-invalid");
        repo.expire(key, Duration.ZERO).onComplete(ctx.failing(err -> ctx.verify(() -> {
            assertTrue(err instanceof IllegalArgumentException);
            ctx.completeNow();
        })));
    }

    @Test
    @Order(26)
    void persistReturnsFalseForMissingCounter(VertxTestContext ctx) {
        CacheKey key = new CacheKey("ns", "persist-missing");
        repo.persist(key).onComplete(ctx.succeeding(updated -> ctx.verify(() -> {
            assertFalse(updated);
            ctx.completeNow();
        })));
    }

    // --- SET VALUE ---

    @Test
    @Order(30)
    void setValueCreatesCounter(VertxTestContext ctx) {
        CacheKey key = new CacheKey("ns", "set-ctr");
        CounterOptions opts = CounterOptions.defaults();
        repo.setValue(key, 42, opts).onComplete(ctx.succeeding(value -> ctx.verify(() -> {
            assertEquals(42L, value);
            ctx.completeNow();
        })));
    }

    @Test
    @Order(31)
    void setValueOverwritesExisting(VertxTestContext ctx) {
        CacheKey key = new CacheKey("ns", "set-over");
        CounterOptions opts = CounterOptions.defaults();
        repo.increment(key, 10, opts)
                .compose(v -> repo.setValue(key, 99, opts))
                .compose(v -> repo.get(key))
                .onComplete(ctx.succeeding(result -> ctx.verify(() -> {
                    assertEquals(99L, result.orElseThrow());
                    ctx.completeNow();
                })));
    }

    // --- DELETE ---

    @Test
    @Order(40)
    void deleteRemovesCounter(VertxTestContext ctx) {
        CacheKey key = new CacheKey("ns", "del-ctr");
        CounterOptions opts = CounterOptions.defaults();
        repo.increment(key, 1, opts)
                .compose(v -> repo.delete(key))
                .compose(deleted -> {
                    assertTrue(deleted);
                    return repo.get(key);
                })
                .onComplete(ctx.succeeding(result -> ctx.verify(() -> {
                    assertTrue(result.isEmpty());
                    ctx.completeNow();
                })));
    }

    @Test
    @Order(41)
    void deleteOnMissingReturnsFalse(VertxTestContext ctx) {
        CacheKey key = new CacheKey("ns", "del-missing-ctr");
        repo.delete(key).onComplete(ctx.succeeding(deleted -> ctx.verify(() -> {
            assertFalse(deleted);
            ctx.completeNow();
        })));
    }

    @Test
    @Order(50)
    void repositoryUsesConfiguredSchema(VertxTestContext ctx) {
        String schema = "custom_counter_repo";
        PgCounterRepository customRepo = new PgCounterRepository(pool, schema);
        CacheKey key = new CacheKey("ns", "custom-counter-key");

        applySchema(schema)
                .compose(v -> customRepo.increment(key, 7, CounterOptions.defaults()))
                .compose(v -> customRepo.get(key))
                .onComplete(ctx.succeeding(result -> ctx.verify(() -> {
                    assertTrue(result.isPresent());
                    assertEquals(7L, result.get());
                    ctx.completeNow();
                })));
    }

    private static io.vertx.core.Future<Void> applySchema(String schemaName) {
        String sql = "DROP SCHEMA IF EXISTS " + schemaName + " CASCADE;\n"
                + BootstrapSqlRenderer.loadForSchema(schemaName);
        return pool.query(sql).execute().mapEmpty();
    }
}
