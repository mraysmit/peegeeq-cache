package dev.mars.peegeeq.cache.test;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PostgreSQLTestConstantsTest {

    private static final String IMAGE_PROPERTY = "peegeeq.test.postgres.image";
    private final String originalImage = System.getProperty(IMAGE_PROPERTY);

    @AfterEach
    void restoreImageProperty() {
        if (originalImage == null) {
            System.clearProperty(IMAGE_PROPERTY);
        } else {
            System.setProperty(IMAGE_PROPERTY, originalImage);
        }
    }

    @Test
    void usesPostgres18ByDefault() {
        System.clearProperty(IMAGE_PROPERTY);

        assertEquals("postgres:18.3-alpine", PostgreSQLTestConstants.postgresImage());
    }

    @Test
    void acceptsPostgresImageOverrideForCompatibilityTesting() {
        System.setProperty(IMAGE_PROPERTY, "postgres:15-alpine");

        assertEquals("postgres:15-alpine", PostgreSQLTestConstants.postgresImage());
    }
}
