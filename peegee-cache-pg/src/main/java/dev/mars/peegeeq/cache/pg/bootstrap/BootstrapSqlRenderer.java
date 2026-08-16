package dev.mars.peegeeq.cache.pg.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Loads and renders the peegee-cache bootstrap SQL for a target schema.
 *
 * <p>Versioned SQL files live on the classpath and use the default schema
 * name {@code peegee_cache}. This class loads them in version order and
 * optionally replaces the schema token with a caller-supplied name.
 */
public final class BootstrapSqlRenderer {

    public static final String DEFAULT_SCHEMA_NAME = "peegee_cache";
    private static final List<MigrationResource> MIGRATIONS = List.of(
            new MigrationResource(1, "create peegee-cache V1 baseline",
                    "/db/bootstrap/V001__create_peegee_cache_schema.sql"));
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private BootstrapSqlRenderer() {
    }

    /**
     * Loads the bootstrap SQL from the classpath without modification.
     */
    public static String loadBootstrapSql() {
        return MIGRATIONS.stream().map(MigrationResource::loadSql)
                .reduce((left, right) -> left + System.lineSeparator() + right)
                .orElseThrow();
    }

    /**
     * Loads the bootstrap SQL and renders it for the given schema.
     */
    public static String loadForSchema(String schemaName) {
        return forSchema(loadBootstrapSql(), schemaName);
    }

    /** Loads one migration by version and renders it for the target schema. */
    public static String loadMigrationForSchema(int version, String schemaName) {
        MigrationResource migration = MIGRATIONS.stream()
                .filter(candidate -> candidate.version() == version)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown schema migration version: " + version));
        return forSchema(migration.loadSql(), schemaName);
    }

    /** Returns every bundled migration, rendered and ordered by version. */
    public static List<SchemaMigration> migrationsForSchema(String schemaName) {
        String schema = requireSchema(schemaName);
        return MIGRATIONS.stream()
                .map(resource -> new SchemaMigration(resource.version(), resource.description(),
                        forSchema(resource.loadSql(), schema)))
                .toList();
    }

    /**
     * Renders already-loaded bootstrap SQL for the given schema.
     */
    public static String forSchema(String bootstrapSql, String schemaName) {
        Objects.requireNonNull(bootstrapSql, "bootstrapSql");
        String schema = requireSchema(schemaName);
        return bootstrapSql.replace(DEFAULT_SCHEMA_NAME, schema);
    }

    private static String requireSchema(String schemaName) {
        String value = Objects.requireNonNull(schemaName, "schemaName").trim();
        if (!IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid PostgreSQL schema name: " + schemaName);
        }
        return value;
    }

    /** Immutable rendered migration descriptor. */
    public record SchemaMigration(int version, String description, String sql) {
    }

    private record MigrationResource(int version, String description, String resource) {
        private String loadSql() {
            try (InputStream input = BootstrapSqlRenderer.class.getResourceAsStream(resource)) {
                if (input == null) {
                    throw new IllegalStateException("Bootstrap SQL not found on classpath: " + resource);
                }
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException failure) {
                throw new IllegalStateException("Failed to read bootstrap SQL: " + resource, failure);
            }
        }
    }
}
