package dev.mars.peegeeq.cache.benchmark;

import java.util.Objects;

/** Explicit target identity and ownership boundary; credentials are intentionally absent. */
public record BenchmarkDeploymentTarget(Kind kind, String id, String host, int port, String database,
                                        String schemaPrefix, boolean tlsRequired,
                                        boolean serverOwned, String image) {
    public enum Kind { LOCAL_TESTCONTAINERS, EXTERNAL }

    public BenchmarkDeploymentTarget {
        Objects.requireNonNull(kind, "kind");
        id = named(id, "target id");
        schemaPrefix = identifier(schemaPrefix, "schema prefix");
        if (kind == Kind.EXTERNAL) {
            host = named(host, "host");
            database = named(database, "database");
            if (port < 1 || port > 65_535 || serverOwned || image != null) {
                throw new IllegalArgumentException("External target endpoint and ownership are invalid");
            }
            if ("public".equalsIgnoreCase(schemaPrefix)) {
                throw new IllegalArgumentException("External target must use an explicit isolated resource prefix");
            }
        } else {
            image = named(image, "PostgreSQL image");
            if (!serverOwned || host != null || database != null || port != 0) {
                throw new IllegalArgumentException("Local target must own only its disposable container");
            }
        }
    }

    public static BenchmarkDeploymentTarget external(String id, String host, int port, String database,
                                                     String schemaPrefix, boolean tlsRequired) {
        return new BenchmarkDeploymentTarget(Kind.EXTERNAL, id, host, port, database, schemaPrefix,
                tlsRequired, false, null);
    }

    public static BenchmarkDeploymentTarget localTestcontainers(String image, String schemaPrefix) {
        return new BenchmarkDeploymentTarget(Kind.LOCAL_TESTCONTAINERS, "local-testcontainers",
                null, 0, null, schemaPrefix, false, true, image);
    }

    private static String named(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }

    private static String identifier(String value, String field) {
        named(value, field);
        if (!value.matches("[a-z][a-z0-9_]{0,39}")) {
            throw new IllegalArgumentException(field + " must be a bounded lowercase SQL identifier prefix");
        }
        return value;
    }
}
