package dev.mars.peegeeq.cache.pg.repository;

import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.CounterOptions;
import dev.mars.peegeeq.cache.api.model.TtlResult;
import dev.mars.peegeeq.cache.pg.sql.CounterSql;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * PostgreSQL implementation of counter repository operations.
 */
public final class PgCounterRepository {

    private static final Logger log = LoggerFactory.getLogger(PgCounterRepository.class);

    private final Pool pool;
    private final CounterSql sql;

    public PgCounterRepository(Pool pool, String schemaName) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.sql = CounterSql.forSchema(schemaName);
    }

    /** Section 17.1 — returns the live counter value, or empty if missing/expired. */
    public Future<Optional<Long>> get(CacheKey key) {
        log.debug("get counter key={}", key);
        return pool.preparedQuery(sql.GET)
                .execute(Tuple.of(key.namespace(), key.key()))
                .map(rows -> {
                    var it = rows.iterator();
                    if (!it.hasNext()) {
                        log.debug("get counter key={} -> miss", key);
                        return Optional.<Long>empty();
                    }
                    long val = it.next().getLong("counter_value");
                    log.debug("get counter key={} -> {}", key, val);
                    return Optional.of(val);
                });
    }

    /**
     * Increment (or decrement) a counter by delta.
     * <p>
     * When {@code createIfMissing = true}, this is an upsert that also
     * handles expired-row cleanup (Section 17.3). The TTL mode controls
     * expiry behavior on the conflict branch.
     * <p>
     * When {@code createIfMissing = false}, uses a pure UPDATE (Section 17.2a)
     * and returns null if the counter does not exist.
     */
    public Future<Long> increment(CacheKey key, long delta, CounterOptions opts) {
        log.debug("increment counter key={} delta={} ttlMode={} createIfMissing={}",
                key, delta, opts.ttlMode(), opts.createIfMissing());
        if (!opts.createIfMissing()) {
            return incrementNoCreate(key, delta);
        }
        return incrementWithCreate(key, delta, opts);
    }

    /** Section 17.4 — set exact counter value via upsert. */
    public Future<Long> setValue(CacheKey key, long value, CounterOptions opts) {
        log.debug("setValue counter key={} value={}", key, value);
        Tuple params = Tuple.of(key.namespace(), key.key(), value, ttlMillis(opts.ttl()));
        return pool.preparedQuery(sql.SET_VALUE)
                .execute(params)
                .map(rows -> rows.iterator().next().getLong("counter_value"));
    }

    /** Unconditional delete. Returns true if a row was removed. */
    public Future<Boolean> delete(CacheKey key) {
        log.debug("delete counter key={}", key);
        return pool.preparedQuery(sql.DELETE)
                .execute(Tuple.of(key.namespace(), key.key()))
                .map(rows -> {
                    boolean deleted = rows.rowCount() > 0;
                    log.debug("delete counter key={} -> removed={}", key, deleted);
                    return deleted;
                });
    }

    /** Returns TTL state for a live counter. */
    public Future<TtlResult> ttl(CacheKey key) {
        log.debug("ttl counter key={}", key);
        return pool.preparedQuery(sql.TTL)
                .execute(Tuple.of(key.namespace(), key.key()))
                .map(rows -> {
                    var it = rows.iterator();
                    if (!it.hasNext()) {
                        return TtlResult.missing();
                    }
                    Long ttlMillis = it.next().getLong("ttl_millis");
                    if (ttlMillis == null) {
                        return TtlResult.persistent();
                    }
                    return TtlResult.expiring(Math.max(0L, ttlMillis));
                });
    }

    /** Sets TTL for a live counter. */
    public Future<Boolean> expire(CacheKey key, Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            return Future.failedFuture(new IllegalArgumentException("ttl must be > 0"));
        }
        long ttlMillis = ttl.toMillis();
        log.debug("expire counter key={} ttlMillis={}", key, ttlMillis);
        return pool.preparedQuery(sql.EXPIRE)
                .execute(Tuple.of(key.namespace(), key.key(), ttlMillis))
                .map(rows -> rows.rowCount() > 0);
    }

    /** Removes TTL from a live counter. */
    public Future<Boolean> persist(CacheKey key) {
        log.debug("persist counter key={}", key);
        return pool.preparedQuery(sql.PERSIST)
                .execute(Tuple.of(key.namespace(), key.key()))
                .map(rows -> rows.rowCount() > 0);
    }

    // ---- Private helpers ----

    private Future<Long> incrementNoCreate(CacheKey key, long delta) {
        return pool.preparedQuery(sql.INCREMENT_NO_CREATE)
                .execute(Tuple.of(key.namespace(), key.key(), delta))
                .map(rows -> {
                    if (rows.rowCount() == 0) {
                        return null;
                    }
                    return rows.iterator().next().getLong("counter_value");
                });
    }

    private Future<Long> incrementWithCreate(CacheKey key, long delta, CounterOptions opts) {
        String sql = switch (opts.ttlMode()) {
            case PRESERVE_EXISTING -> this.sql.INCREMENT_PRESERVE;
            case REPLACE -> this.sql.INCREMENT_REPLACE;
            case REMOVE -> this.sql.INCREMENT_REMOVE;
        };

        Tuple keyTuple = Tuple.of(key.namespace(), key.key());
        Tuple params = Tuple.of(key.namespace(), key.key(), delta, ttlMillis(opts.ttl()));

        return pool.withTransaction(conn ->
                conn.preparedQuery(this.sql.DELETE_EXPIRED)
                        .execute(keyTuple)
                        .compose(ignored ->
                                conn.preparedQuery(sql)
                                        .execute(params)
                                        .map(rows -> rows.iterator().next().getLong("counter_value"))
                        )
        );
    }

    private static Long ttlMillis(Duration ttl) {
        if (ttl == null) {
            return null;
        }
        return ttl.toMillis();
    }
}
