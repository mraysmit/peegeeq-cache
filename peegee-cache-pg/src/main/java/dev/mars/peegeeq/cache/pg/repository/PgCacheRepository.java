package dev.mars.peegeeq.cache.pg.repository;

import dev.mars.peegeeq.cache.api.model.CacheEntry;
import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.CacheSetRequest;
import dev.mars.peegeeq.cache.api.model.CacheSetResult;
import dev.mars.peegeeq.cache.api.model.CacheValue;
import dev.mars.peegeeq.cache.api.model.TouchResult;
import dev.mars.peegeeq.cache.api.model.TtlResult;
import dev.mars.peegeeq.cache.api.model.ValueType;
import dev.mars.peegeeq.cache.pg.sql.CacheSql;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * PostgreSQL implementation of cache entry repository operations.
 * Uses the Vert.x reactive PgPool and parameterised SQL from {@link CacheSql}.
 */
public final class PgCacheRepository {

    private static final Logger log = LoggerFactory.getLogger(PgCacheRepository.class);

    private final Pool pool;
    private final CacheSql sql;

    public PgCacheRepository(Pool pool, String schemaName) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.sql = CacheSql.forSchema(schemaName);
    }

    /** Section 16.1 — returns a live entry, or empty if missing/expired. */
    public Future<Optional<CacheEntry>> get(CacheKey key) {
        log.debug("get key={}", key);
        return pool.preparedQuery(sql.GET)
                .execute(Tuple.of(key.namespace(), key.key()))
                .map(rows -> {
                    var it = rows.iterator();
                    if (!it.hasNext()) {
                        log.debug("get key={} -> miss", key);
                        return Optional.<CacheEntry>empty();
                    }
                    log.debug("get key={} -> hit", key);
                    return Optional.of(mapRow(it.next()));
                });
    }

    /** Section 16.3 — unconditional delete. Returns true if a row was removed. */
    public Future<Boolean> delete(CacheKey key) {
        log.debug("delete key={}", key);
        return pool.preparedQuery(sql.DELETE)
                .execute(Tuple.of(key.namespace(), key.key()))
                .map(rows -> {
                    boolean deleted = rows.rowCount() > 0;
                    log.debug("delete key={} -> removed={}", key, deleted);
                    return deleted;
                });
    }

    /** Section 16.2 — true when a live entry exists. */
    public Future<Boolean> exists(CacheKey key) {
        log.debug("exists key={}", key);
        return pool.preparedQuery(sql.EXISTS)
                .execute(Tuple.of(key.namespace(), key.key()))
                .map(rows -> rows.iterator().next().getBoolean(0));
    }

    /** Section 16.8 — ttl lookup for live entries. */
    public Future<TtlResult> ttl(CacheKey key) {
        log.debug("ttl key={}", key);
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

    /** Section 16.9 — set ttl on live entry. */
    public Future<Boolean> expire(CacheKey key, long ttlMillis) {
        log.debug("expire key={} ttlMillis={}", key, ttlMillis);
        return pool.preparedQuery(sql.EXPIRE)
                .execute(Tuple.of(key.namespace(), key.key(), ttlMillis))
                .map(rows -> rows.rowCount() > 0);
    }

    /** Section 16.10 — remove ttl from live entry. */
    public Future<Boolean> persist(CacheKey key) {
        log.debug("persist key={}", key);
        return pool.preparedQuery(sql.PERSIST)
                .execute(Tuple.of(key.namespace(), key.key()))
                .map(rows -> rows.rowCount() > 0);
    }

    /** Section 16.11 — touch entry and optionally refresh ttl. */
    public Future<TouchResult> touch(CacheKey key, Long ttlMillis) {
        log.debug("touch key={} ttlMillis={}", key, ttlMillis);
        return pool.preparedQuery(sql.TOUCH)
                .execute(Tuple.of(key.namespace(), key.key(), ttlMillis))
                .map(rows -> {
                    var it = rows.iterator();
                    if (!it.hasNext()) {
                        return new TouchResult(false, TtlResult.missing());
                    }
                    Long ttl = it.next().getLong("ttl_millis");
                    if (ttl == null) {
                        return new TouchResult(true, TtlResult.persistent());
                    }
                    return new TouchResult(true, TtlResult.expiring(Math.max(0L, ttl)));
                });
    }

    /**
     * Routes to the correct SQL strategy based on {@link SetMode}.
     * Supports UPSERT, ONLY_IF_ABSENT, ONLY_IF_PRESENT, and
     * ONLY_IF_VERSION_MATCHES, including returnPreviousValue semantics.
     */
    public Future<CacheSetResult> set(CacheSetRequest req) {
        log.debug("set key={} mode={} returnPrev={}", req.key(), req.mode(), req.returnPreviousValue());
        return switch (req.mode()) {
            case UPSERT -> setUpsert(req);
            case ONLY_IF_ABSENT -> setIfAbsent(req);
            case ONLY_IF_PRESENT -> setIfPresent(req);
            case ONLY_IF_VERSION_MATCHES -> setIfVersionMatches(req);
        };
    }

    // ---- UPSERT ----

    private Future<CacheSetResult> setUpsert(CacheSetRequest req) {
        if (req.returnPreviousValue()) {
            return setUpsertWithPrevious(req);
        }
        Tuple params = valueTuple(req);
        return pool.preparedQuery(sql.UPSERT)
                .execute(params)
                .map(rows -> {
                    Row row = rows.iterator().next();
                    long version = row.getLong("version");
                    return new CacheSetResult(true, version, null);
                });
    }

    private Future<CacheSetResult> setUpsertWithPrevious(CacheSetRequest req) {
        CacheKey key = req.key();
        Tuple keyTuple = Tuple.of(key.namespace(), key.key());
        Tuple params = valueTuple(req);

        return pool.withTransaction(conn ->
                conn.preparedQuery(sql.GET_FOR_UPDATE)
                        .execute(keyTuple)
                        .compose(prevRows -> {
                            var it = prevRows.iterator();
                            CacheEntry prev = it.hasNext()
                                    ? mapRow(it.next())
                                    : null;
                                return conn.preparedQuery(sql.UPSERT)
                                    .execute(params)
                                    .map(rows -> {
                                        long version = rows.iterator().next().getLong("version");
                                        return new CacheSetResult(true, version, prev);
                                    });
                        })
        );
    }

    // ---- ONLY_IF_ABSENT (NX) ----

    private Future<CacheSetResult> setIfAbsent(CacheSetRequest req) {
        CacheKey key = req.key();
        Tuple keyTuple = Tuple.of(key.namespace(), key.key());
        Tuple params = valueTuple(req);

        return pool.withTransaction(conn ->
                conn.preparedQuery(sql.DELETE_EXPIRED)
                        .execute(keyTuple)
                        .compose(ignored ->
                        conn.preparedQuery(sql.INSERT_IF_ABSENT)
                                        .execute(params)
                                        .map(rows -> {
                                            if (rows.rowCount() == 0) {
                                                return new CacheSetResult(false, 0, null);
                                            }
                                            long version = rows.iterator().next().getLong("version");
                                            return new CacheSetResult(true, version, null);
                                        })
                        )
        );
    }

    // ---- ONLY_IF_PRESENT (XX) ----

    private Future<CacheSetResult> setIfPresent(CacheSetRequest req) {
        Tuple params = valueTuple(req);
        return pool.preparedQuery(sql.UPDATE_IF_PRESENT)
                .execute(params)
                .map(rows -> {
                    if (rows.rowCount() == 0) {
                        return new CacheSetResult(false, 0, null);
                    }
                    long version = rows.iterator().next().getLong("version");
                    return new CacheSetResult(true, version, null);
                });
    }

    // ---- ONLY_IF_VERSION_MATCHES (CAS) ----

    private Future<CacheSetResult> setIfVersionMatches(CacheSetRequest req) {
        CacheKey key = req.key();
        Long expectedVersion = req.expectedVersion();
        if (expectedVersion == null) {
            return Future.failedFuture(
                    new IllegalArgumentException("expectedVersion required for ONLY_IF_VERSION_MATCHES"));
        }
        Tuple params = Tuple.of(
                key.namespace(), key.key(), expectedVersion,
                req.value().type().name(),
                valueBytes(req.value()),
                numericValue(req.value()),
                ttlMillis(req.ttl())
        );
        return pool.preparedQuery(sql.UPDATE_IF_VERSION_MATCHES)
                .execute(params)
                .map(rows -> {
                    if (rows.rowCount() == 0) {
                        return new CacheSetResult(false, 0, null);
                    }
                    long version = rows.iterator().next().getLong("version");
                    return new CacheSetResult(true, version, null);
                });
    }

    // ---- Row mapping ----

    private static CacheEntry mapRow(Row row) {
        return CacheEntryMapper.mapRow(row);
    }

    // ---- Parameter helpers ----

    /** Build the 6-element tuple: namespace, key, valueType, valueBytes, numericValue, expiresAt. */
    private static Tuple valueTuple(CacheSetRequest req) {
        CacheKey key = req.key();
        return Tuple.of(
                key.namespace(),
                key.key(),
                req.value().type().name(),
                valueBytes(req.value()),
                numericValue(req.value()),
                ttlMillis(req.ttl())
        );
    }

    private static Buffer valueBytes(CacheValue v) {
        return v.type() == ValueType.LONG ? null : v.binaryValue();
    }

    private static Long numericValue(CacheValue v) {
        return v.type() == ValueType.LONG ? v.longValue() : null;
    }

    private static Long ttlMillis(Duration ttl) {
        if (ttl == null) {
            return null;
        }
        return ttl.toMillis();
    }
}
