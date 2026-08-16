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
    private static final String CHECK_SQL = """
            WITH required_objects(object_name, present) AS (
                VALUES
                    ('cache_entries table', to_regclass($1::text || '.cache_entries') IS NOT NULL),
                    ('cache_counters table', to_regclass($1::text || '.cache_counters') IS NOT NULL),
                    ('cache_locks table', to_regclass($1::text || '.cache_locks') IS NOT NULL),
                    ('schema_migrations table', to_regclass($1::text || '.schema_migrations') IS NOT NULL),
                    ('live_entries view', to_regclass($1::text || '.live_entries') IS NOT NULL),
                    ('live_counters view', to_regclass($1::text || '.live_counters') IS NOT NULL),
                    ('active_locks view', to_regclass($1::text || '.active_locks') IS NOT NULL),
                    ('lock_fencing_seq sequence', to_regclass($1::text || '.lock_fencing_seq') IS NOT NULL),
                    ('idx_cache_entries_expires_at index', to_regclass($1::text || '.idx_cache_entries_expires_at') IS NOT NULL),
                    ('idx_cache_entries_namespace_key_pattern index', to_regclass($1::text || '.idx_cache_entries_namespace_key_pattern') IS NOT NULL),
                    ('idx_cache_counters_expires_at index', to_regclass($1::text || '.idx_cache_counters_expires_at') IS NOT NULL),
                    ('idx_cache_counters_namespace_key_pattern index', to_regclass($1::text || '.idx_cache_counters_namespace_key_pattern') IS NOT NULL),
                    ('idx_cache_locks_lease_expires_at index', to_regclass($1::text || '.idx_cache_locks_lease_expires_at') IS NOT NULL),
                    ('acquire_lock function', to_regprocedure($1::text || '.acquire_lock(text,text,text,bigint,boolean,boolean)') IS NOT NULL),
                    ('renew_lock function', to_regprocedure($1::text || '.renew_lock(text,text,text,bigint)') IS NOT NULL),
                    ('release_lock function', to_regprocedure($1::text || '.release_lock(text,text,text)') IS NOT NULL),
                    ('increment_counter function', to_regprocedure($1::text || '.increment_counter(text,text,bigint,bigint,text,boolean)') IS NOT NULL),
                    ('set_counter function', to_regprocedure($1::text || '.set_counter(text,text,bigint,bigint)') IS NOT NULL),
                    ('delete_counter function', to_regprocedure($1::text || '.delete_counter(text,text)') IS NOT NULL),
                    ('set_entry function', to_regprocedure($1::text || '.set_entry(text,text,text,bytea,bigint,bigint,text,bigint)') IS NOT NULL),
                    ('delete_entry function', to_regprocedure($1::text || '.delete_entry(text,text)') IS NOT NULL)
            )
            SELECT bool_and(present) AS schema_ready,
                   string_agg(object_name, ', ' ORDER BY object_name)
                       FILTER (WHERE NOT present) AS missing_objects
            FROM required_objects
            """;
    private final Pool pool;
    private final String schemaName;
    private final BooleanSupplier runtimeStarted;

    public PgCacheHealthIndicator(Pool pool, String schemaName, BooleanSupplier runtimeStarted) {
        this.pool = Objects.requireNonNull(pool, "pool");
        String schema = Objects.requireNonNull(schemaName, "schemaName").trim();
        if (!IDENTIFIER.matcher(schema).matches()) {
            throw new IllegalArgumentException("Invalid PostgreSQL schema name: " + schemaName);
        }
        this.schemaName = schema;
        this.runtimeStarted = Objects.requireNonNull(runtimeStarted, "runtimeStarted");
    }

    public Future<CacheHealth> check() {
        Instant checkedAt = Instant.now();
        if (!runtimeStarted.getAsBoolean()) {
            return Future.succeededFuture(new CacheHealth(CacheHealth.Status.STOPPED, false,
                    Duration.ZERO, checkedAt, "managed runtime is stopped"));
        }
        long startedAt = System.nanoTime();
        return pool.preparedQuery(CHECK_SQL).execute(Tuple.of(schemaName))
                .map(rows -> {
                    var row = rows.iterator().next();
                    boolean ready = Boolean.TRUE.equals(row.getBoolean("schema_ready"));
                    String missingObjects = row.getString("missing_objects");
                    return new CacheHealth(ready ? CacheHealth.Status.UP : CacheHealth.Status.DOWN,
                            ready, elapsed(startedAt), checkedAt,
                            ready ? "database and schema are ready"
                                    : "missing required schema objects: " + missingObjects);
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
