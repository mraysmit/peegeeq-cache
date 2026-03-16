package dev.mars.peegeeq.cache.pg.test;

/**
 * Centralized PostgreSQL test container constants for peegee-cache integration tests.
 */
public final class PostgreSQLTestConstants {

    public static final String POSTGRES_IMAGE = "postgres:18.3-alpine";
    public static final String DEFAULT_DATABASE_NAME = "testdb";
    public static final String DEFAULT_USERNAME = "test";
    public static final String DEFAULT_PASSWORD = "test";

    private PostgreSQLTestConstants() {
        throw new UnsupportedOperationException("Utility class");
    }
}
