package dev.mars.peegeeq.cache.test;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PostgreSQLTestConstantsTest {

    @Test
    void usesPostgres18ByDefault() {
        assertEquals("postgres:18.3-alpine",
                PostgreSQLTestConstants.postgresImage(new Properties()));
    }

    @Test
    void acceptsPostgresImageOverrideForCompatibilityTesting() {
        Properties properties = new Properties();
        properties.setProperty(PostgreSQLTestConstants.POSTGRES_IMAGE_PROPERTY, "postgres:15-alpine");

        assertEquals("postgres:15-alpine", PostgreSQLTestConstants.postgresImage(properties));
    }
}
