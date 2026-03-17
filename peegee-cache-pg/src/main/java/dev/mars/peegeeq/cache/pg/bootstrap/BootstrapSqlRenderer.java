package dev.mars.peegeeq.cache.pg.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Loads and renders the peegee-cache bootstrap SQL for a target schema.
 *
 * <p>The bootstrap SQL file lives on the classpath at
 * {@value #BOOTSTRAP_SQL_RESOURCE} and uses the default schema name
 * {@code peegee_cache}. This class loads that file and optionally
 * replaces the schema token with a caller-supplied name.
 */
public final class BootstrapSqlRenderer {

    public static final String DEFAULT_SCHEMA_NAME = "peegee_cache";
    private static final String BOOTSTRAP_SQL_RESOURCE = "/db/bootstrap/V001__create_peegee_cache_schema.sql";
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private BootstrapSqlRenderer() {
    }

    /**
     * Loads the bootstrap SQL from the classpath without modification.
     */
    public static String loadBootstrapSql() {
        try (InputStream is = BootstrapSqlRenderer.class.getResourceAsStream(BOOTSTRAP_SQL_RESOURCE)) {
            if (is == null) {
                throw new IllegalStateException(
                        "Bootstrap SQL not found on classpath: " + BOOTSTRAP_SQL_RESOURCE);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read bootstrap SQL", e);
        }
    }

    /**
     * Loads the bootstrap SQL and renders it for the given schema.
     */
    public static String loadForSchema(String schemaName) {
        return forSchema(loadBootstrapSql(), schemaName);
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
}
