package dev.mars.peegeeq.cache.core.validation;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CoreValidationTest {

    @Test
    void requireNonNullRejectsNull() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> CoreValidation.requireNonNull(null, "request")
        );
        assertEquals("request cannot be null", ex.getMessage());
    }

    @Test
    void requireNonBlankRejectsBlank() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> CoreValidation.requireNonBlank("   ", "ownerToken")
        );
        assertEquals("ownerToken must not be blank", ex.getMessage());
    }

    @Test
    void requirePositiveDurationRejectsNullOrNonPositive() {
        IllegalArgumentException nullEx = assertThrows(
                IllegalArgumentException.class,
                () -> CoreValidation.requirePositiveDuration(null, "ttl")
        );
        assertEquals("ttl must be > 0", nullEx.getMessage());

        IllegalArgumentException zeroEx = assertThrows(
                IllegalArgumentException.class,
                () -> CoreValidation.requirePositiveDuration(Duration.ZERO, "ttl")
        );
        assertEquals("ttl must be > 0", zeroEx.getMessage());
    }

    @Test
    void requireOptionalPositiveDurationAllowsNull() {
        assertNull(CoreValidation.requireOptionalPositiveDuration(null, "ttl"));
    }
}
