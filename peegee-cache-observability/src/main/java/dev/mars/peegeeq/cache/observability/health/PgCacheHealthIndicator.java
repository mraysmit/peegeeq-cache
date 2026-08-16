package dev.mars.peegeeq.cache.observability.health;

import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/** Readiness check covering managed lifecycle, database connectivity, and schema presence. */
public final class PgCacheHealthIndicator {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final String CHECK_SQL = "SELECT to_regclass($1) IS NOT NULL AS schema_ready";
    private final Pool pool;
    private final String entriesTable;
    private final BooleanSupplier runtimeStarted;

    public PgCacheHealthIndicator(Pool pool, String schemaName, BooleanSupplier runtimeStarted) {
        this.pool = Objects.requireNonNull(pool, "pool");
        String schema = Objects.requireNonNull(schemaName, "schemaName").trim();
        if (!IDENTIFIER.matcher(schema).matches()) {
            throw new IllegalArgumentException("Invalid PostgreSQL schema name: " + schemaName);
        }
        this.entriesTable = schema + ".cache_entries";
        this.runtimeStarted = Objects.requireNonNull(runtimeStarted, "runtimeStarted");
    }

    public Future<CacheHealth> check() {
        Instant checkedAt = Instant.now();
        if (!runtimeStarted.getAsBoolean()) {
            return Future.succeededFuture(new CacheHealth(CacheHealth.Status.STOPPED, false,
                    Duration.ZERO, checkedAt, "managed runtime is stopped"));
        }
        long startedAt = System.nanoTime();
        return pool.preparedQuery(CHECK_SQL).execute(Tuple.of(entriesTable))
                .map(rows -> {
                    boolean ready = rows.iterator().next().getBoolean("schema_ready");
                    return new CacheHealth(ready ? CacheHealth.Status.UP : CacheHealth.Status.DOWN,
                            ready, elapsed(startedAt), checkedAt,
                            ready ? "database and schema are ready" : "cache_entries table is missing");
                })
                .recover(failure -> Future.succeededFuture(new CacheHealth(CacheHealth.Status.DOWN,
                        false, elapsed(startedAt), checkedAt, safeMessage(failure))));
    }

    private static Duration elapsed(long startedAt) {
        return Duration.ofNanos(Math.max(0, System.nanoTime() - startedAt));
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
