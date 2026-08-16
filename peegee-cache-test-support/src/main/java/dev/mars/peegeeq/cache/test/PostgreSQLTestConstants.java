package dev.mars.peegeeq.cache.test;

/** Stable PostgreSQL defaults shared by peegee-cache integration suites. */
public final class PostgreSQLTestConstants {

    public static final String POSTGRES_IMAGE = "postgres:18.3-alpine";
    public static final String DEFAULT_DATABASE_NAME = "testdb";
    public static final String DEFAULT_USERNAME = "test";
    public static final String DEFAULT_PASSWORD = "test";

    private PostgreSQLTestConstants() {
        throw new UnsupportedOperationException("Utility class");
    }
}
