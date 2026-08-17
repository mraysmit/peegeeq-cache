package dev.mars.peegeeq.cache.pg.repository;

import dev.mars.peegeeq.cache.api.model.LockAcquireRequest;
import dev.mars.peegeeq.cache.api.model.LockAcquireResult;
import dev.mars.peegeeq.cache.api.model.LockKey;
import dev.mars.peegeeq.cache.api.model.LockReleaseRequest;
import dev.mars.peegeeq.cache.api.model.LockRenewRequest;
import dev.mars.peegeeq.cache.api.model.LockState;
import dev.mars.peegeeq.cache.pg.logging.SafeLogValue;
import dev.mars.peegeeq.cache.pg.sql.LockSql;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 * PostgreSQL implementation of lock repository operations.
 * <p>
 * Locks are lease-based only — no permanent locks. Lease TTL is always
 * computed server-side via {@code NOW() + interval}.
 */
public final class PgLockRepository {

    private static final Logger log = LoggerFactory.getLogger(PgLockRepository.class);

    private final Pool pool;
    private final LockSql sql;

    public PgLockRepository(Pool pool, String schemaName) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.sql = LockSql.forSchema(schemaName);
    }

    /**
     * Attempt to acquire a lock.
     * <p>
     * If {@code reentrantForSameOwner} is true and the lock is already held by
     * the same owner, the lease is renewed instead of failing.
     */
    public Future<LockAcquireResult> acquire(LockAcquireRequest req) {
        LockKey key = req.key();
        String owner = req.ownerToken();
        long ttlMs = req.leaseTtl().toMillis();
        String logKey = traceKey(key);
        if (logKey != null) {
            log.atTrace().addKeyValue("lock.key", logKey)
                    .addKeyValue("ttl.ms", ttlMs)
                    .addKeyValue("reentrant", req.reentrantForSameOwner())
                    .addKeyValue("fencing", req.issueFencingToken())
                    .log("lock.acquire.request");
        }

        Tuple keyTuple = Tuple.of(key.namespace(), key.key());
        Tuple insertParams = Tuple.of(key.namespace(), key.key(), owner, ttlMs);

        String insertSql = req.issueFencingToken()
            ? sql.INSERT_WITH_FENCING
            : sql.INSERT_NO_FENCING;

        return pool.withTransaction(conn ->
                // Step 1: delete stale lease
                conn.preparedQuery(sql.DELETE_STALE)
                        .execute(keyTuple)
                        .compose(ignored ->
                                // Step 2: try insert
                                conn.preparedQuery(insertSql)
                                        .execute(insertParams)
                                        .compose(rows -> {
                                            if (rows.rowCount() > 0) {
                                                traceResult("lock.acquire.result", logKey, "acquired");
                                                return Future.succeededFuture(mapAcquireResult(rows.iterator().next(), true));
                                            }
                                            // Conflict — lock already held
                                            if (req.reentrantForSameOwner()) {
                                                // Try reentrant renew by same owner
                                                Tuple renewParams = Tuple.of(
                                                        key.namespace(), key.key(), owner, ttlMs);
                                                return conn.preparedQuery(sql.REENTRANT_RENEW)
                                                        .execute(renewParams)
                                                        .map(renewRows -> {
                                                            if (renewRows.rowCount() > 0) {
                                                                traceResult("lock.acquire.result", logKey, "reentrant_renewal");
                                                                return mapAcquireResult(renewRows.iterator().next(), true);
                                                            }
                                                            traceResult("lock.acquire.result", logKey, "denied_other_owner");
                                                            return notAcquired(key);
                                                        });
                                            }
                                            traceResult("lock.acquire.result", logKey, "denied_already_held");
                                            return Future.succeededFuture(notAcquired(key));
                                        })
                        )
        );
    }

    /** Section 18.3 — renew lease by owner. Returns true if renewed. */
    public Future<Boolean> renew(LockRenewRequest req) {
        LockKey key = req.key();
        long ttlMs = req.leaseTtl().toMillis();
        String logKey = traceKey(key);
        if (logKey != null) {
            log.atTrace().addKeyValue("lock.key", logKey)
                    .addKeyValue("ttl.ms", ttlMs).log("lock.renew.request");
        }
        Tuple params = Tuple.of(key.namespace(), key.key(), req.ownerToken(), ttlMs);
        return pool.preparedQuery(sql.RENEW)
                .execute(params)
                .map(rows -> {
                    boolean renewed = rows.rowCount() > 0;
                    if (logKey != null) {
                        log.atTrace().addKeyValue("lock.key", logKey)
                                .addKeyValue("renewed", renewed).log("lock.renew.result");
                    }
                    return renewed;
                });
    }

    /** Section 18.4 — release lock by owner. Returns true if released. */
    public Future<Boolean> release(LockReleaseRequest req) {
        LockKey key = req.key();
        String logKey = traceKey(key);
        trace("lock.release.request", logKey);
        Tuple params = Tuple.of(key.namespace(), key.key(), req.ownerToken());
        return pool.preparedQuery(sql.RELEASE)
                .execute(params)
                .map(rows -> {
                    boolean released = rows.rowCount() > 0;
                    if (logKey != null) {
                        log.atTrace().addKeyValue("lock.key", logKey)
                                .addKeyValue("released", released).log("lock.release.result");
                    }
                    return released;
                });
    }

    /** Section 18.5 — read current active lock state. */
    public Future<Optional<LockState>> currentLock(LockKey key) {
        return pool.preparedQuery(sql.CURRENT)
                .execute(Tuple.of(key.namespace(), key.key()))
                .map(rows -> {
                    var it = rows.iterator();
                    if (!it.hasNext()) {
                        return Optional.<LockState>empty();
                    }
                    return Optional.of(mapLockState(it.next()));
                });
    }

    // ---- Row mapping ----

    private static LockAcquireResult mapAcquireResult(Row row, boolean acquired) {
        OffsetDateTime leaseExpires = row.getOffsetDateTime("lease_expires_at");
        return new LockAcquireResult(
                acquired,
                new LockKey(row.getString("namespace"), row.getString("lock_key")),
                row.getString("owner_token"),
                row.getLong("fencing_token"),
                leaseExpires != null ? leaseExpires.toInstant() : null
        );
    }

    private static LockAcquireResult notAcquired(LockKey key) {
        return new LockAcquireResult(false, key, null, null, null);
    }

    private static LockState mapLockState(Row row) {
        OffsetDateTime created = row.getOffsetDateTime("created_at");
        OffsetDateTime updated = row.getOffsetDateTime("updated_at");
        OffsetDateTime leaseExpires = row.getOffsetDateTime("lease_expires_at");
        return new LockState(
                new LockKey(row.getString("namespace"), row.getString("lock_key")),
                row.getString("owner_token"),
                row.getLong("fencing_token"),
                row.getLong("version"),
                created != null ? created.toInstant() : null,
                updated != null ? updated.toInstant() : null,
                leaseExpires != null ? leaseExpires.toInstant() : null
        );
    }

    private static String traceKey(LockKey key) {
        return log.isTraceEnabled() ? SafeLogValue.identifier(key.asQualifiedKey()) : null;
    }

    private static void trace(String event, String logKey) {
        if (logKey != null) {
            log.atTrace().addKeyValue("lock.key", logKey).log(event);
        }
    }

    private static void traceResult(String event, String logKey, String outcome) {
        if (logKey != null) {
            log.atTrace().addKeyValue("lock.key", logKey)
                    .addKeyValue("outcome", outcome).log(event);
        }
    }
}
