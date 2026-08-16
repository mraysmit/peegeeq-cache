package dev.mars.peegeeq.cache.pg.bootstrap;

import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Transaction;
import io.vertx.sqlclient.Tuple;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Applies bundled peegee-cache schema migrations once, in version order. */
public final class PgSchemaMigrator {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private final Pool pool;
    private final String schemaName;
    private final List<BootstrapSqlRenderer.SchemaMigration> migrations;

    public PgSchemaMigrator(Pool pool, String schemaName) {
        this.pool = Objects.requireNonNull(pool, "pool");
        String schema = Objects.requireNonNull(schemaName, "schemaName").trim();
        if (!IDENTIFIER.matcher(schema).matches()) {
            throw new IllegalArgumentException("Invalid PostgreSQL schema name: " + schemaName);
        }
        this.schemaName = schema;
        this.migrations = BootstrapSqlRenderer.migrationsForSchema(schema);
    }

    public Future<Void> migrate() {
        String lockName = "peegee-cache-schema:" + schemaName;
        return pool.withConnection(connection -> connection
                .preparedQuery("SELECT pg_advisory_lock(hashtext($1))")
                .execute(Tuple.of(lockName))
                .compose(ignored -> ensureMigrationLedger(connection))
                .compose(ignored -> appliedVersions(connection))
                .compose(applied -> applyPending(connection, applied, 0))
                .eventually(() -> connection
                        .preparedQuery("SELECT pg_advisory_unlock(hashtext($1))")
                        .execute(Tuple.of(lockName)).mapEmpty()));
    }

    private Future<Void> ensureMigrationLedger(SqlConnection connection) {
        return connection.query("""
                CREATE SCHEMA IF NOT EXISTS %1$s;
                CREATE TABLE IF NOT EXISTS %1$s.schema_migrations (
                    version INTEGER PRIMARY KEY,
                    description TEXT NOT NULL,
                    applied_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                )
                """.formatted(schemaName)).execute().mapEmpty();
    }

    private Future<Set<Integer>> appliedVersions(SqlConnection connection) {
        return connection.query("SELECT version FROM " + schemaName + ".schema_migrations")
                .execute()
                .map(rows -> {
                    Set<Integer> versions = new HashSet<>();
                    rows.forEach(row -> versions.add(row.getInteger("version")));
                    int latestBundledVersion = migrations.get(migrations.size() - 1).version();
                    int latestAppliedVersion = versions.stream().mapToInt(Integer::intValue).max().orElse(0);
                    if (latestAppliedVersion > latestBundledVersion) {
                        throw new IllegalStateException("Database schema version " + latestAppliedVersion
                                + " is newer than this library (latest bundled version "
                                + latestBundledVersion + ")");
                    }
                    return versions;
                });
    }

    private Future<Void> applyPending(SqlConnection connection, Set<Integer> applied, int index) {
        if (index >= migrations.size()) {
            return Future.succeededFuture();
        }
        BootstrapSqlRenderer.SchemaMigration migration = migrations.get(index);
        if (applied.contains(migration.version())) {
            return applyPending(connection, applied, index + 1);
        }
        return connection.begin()
                .compose(transaction -> applyMigration(connection, transaction, migration))
                .compose(ignored -> {
                    applied.add(migration.version());
                    return applyPending(connection, applied, index + 1);
                });
    }

    private Future<Void> applyMigration(SqlConnection connection, Transaction transaction,
                                        BootstrapSqlRenderer.SchemaMigration migration) {
        return connection.query(migration.sql()).execute()
                .compose(ignored -> connection.preparedQuery("INSERT INTO " + schemaName
                                + ".schema_migrations(version, description) VALUES ($1, $2) "
                                + "ON CONFLICT (version) DO NOTHING")
                        .execute(Tuple.of(migration.version(), migration.description())))
                .compose(ignored -> transaction.commit())
                .recover(failure -> transaction.rollback()
                        .compose(ignored -> Future.failedFuture(failure)));
    }
}
