package dev.mars.peegeeq.cache.runtime.expiry;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;

import java.util.Objects;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Performs bounded physical cleanup of expired cache rows using PostgreSQL time.
 */
public final class PgExpirySweeper {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final Pool pool;
    private final String deleteExpiredEntries;
    private final String deleteExpiredCounters;
    private final String deleteExpiredLocks;
    private final Object sweepLock = new Object();
    private volatile Future<SweepResult> activeSweep = Future.succeededFuture(SweepResult.empty());

    public PgExpirySweeper(Pool pool, String schemaName) {
        this.pool = Objects.requireNonNull(pool, "pool");
        String schema = requireSchema(schemaName);
        this.deleteExpiredEntries = boundedDelete(
                schema + ".cache_entries", "cache_key", "expires_at");
        this.deleteExpiredCounters = boundedDelete(
                schema + ".cache_counters", "counter_key", "expires_at");
        this.deleteExpiredLocks = boundedDelete(
                schema + ".cache_locks", "lock_key", "lease_expires_at");
    }

    /**
     * Deletes at most {@code batchSize} rows from each expirable table.
     * Overlapping timer ticks are coalesced into the already-running sweep.
     */
    public Future<Integer> sweep(int batchSize) {
        return sweepDetailed(batchSize).map(SweepResult::deletedRows);
    }

    /** Returns both the deletion count and the oldest removed row's expiry lag. */
    public Future<SweepResult> sweepDetailed(int batchSize) {
        if (batchSize <= 0) {
            return Future.failedFuture(new IllegalArgumentException("batchSize must be > 0"));
        }
        Promise<SweepResult> completion;
        synchronized (sweepLock) {
            if (!activeSweep.isComplete()) {
                return activeSweep;
            }
            completion = Promise.promise();
            activeSweep = completion.future();
        }

        Tuple params = Tuple.of(batchSize);
        try {
            pool.preparedQuery(deleteExpiredEntries).execute(params)
                    .compose(entries -> pool.preparedQuery(deleteExpiredCounters).execute(params)
                            .compose(counters -> pool.preparedQuery(deleteExpiredLocks).execute(params)
                                    .map(locks -> merge(entries.iterator().next(), counters.iterator().next(),
                                            locks.iterator().next()))))
                    .onComplete(completion);
        } catch (Throwable failure) {
            completion.tryFail(failure);
        }
        return completion.future();
    }

    /** Waits for an already-running sweep without allowing its failure to prevent shutdown. */
    public Future<Void> awaitIdle() {
        Future<SweepResult> sweep;
        synchronized (sweepLock) {
            sweep = activeSweep;
        }
        return sweep.<Void>mapEmpty().recover(ignored -> Future.<Void>succeededFuture());
    }

    private static String boundedDelete(String table, String keyColumn, String expiryColumn) {
        return """
                WITH expired AS (
                    SELECT namespace, %2$s, %3$s
                    FROM %1$s
                    WHERE %3$s <= NOW()
                    ORDER BY %3$s
                    LIMIT $1
                    FOR UPDATE SKIP LOCKED
                ), deleted AS (
                    DELETE FROM %1$s target
                    USING expired
                    WHERE target.namespace = expired.namespace
                      AND target.%2$s = expired.%2$s
                    RETURNING target.%3$s AS expired_at
                )
                SELECT COUNT(*)::int AS deleted_rows,
                       COALESCE(MAX(GREATEST(0,
                           EXTRACT(EPOCH FROM (NOW() - expired_at)) * 1000)), 0)::bigint AS oldest_lag_ms
                FROM deleted
                """.formatted(table, keyColumn, expiryColumn);
    }

    private static SweepResult merge(io.vertx.sqlclient.Row... rows) {
        int deleted = 0;
        long maxLagMillis = 0;
        for (io.vertx.sqlclient.Row row : rows) {
            deleted += row.getInteger("deleted_rows");
            maxLagMillis = Math.max(maxLagMillis, row.getLong("oldest_lag_ms"));
        }
        return new SweepResult(deleted, Duration.ofMillis(maxLagMillis));
    }

    private static String requireSchema(String schemaName) {
        String value = Objects.requireNonNull(schemaName, "schemaName").trim();
        if (!IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid PostgreSQL schema name: " + schemaName);
        }
        return value;
    }

    public record SweepResult(int deletedRows, Duration oldestExpiredRowLag) {
        public SweepResult {
            if (deletedRows < 0) {
                throw new IllegalArgumentException("deletedRows must be >= 0");
            }
            Objects.requireNonNull(oldestExpiredRowLag, "oldestExpiredRowLag");
        }

        public static SweepResult empty() {
            return new SweepResult(0, Duration.ZERO);
        }
    }
}
