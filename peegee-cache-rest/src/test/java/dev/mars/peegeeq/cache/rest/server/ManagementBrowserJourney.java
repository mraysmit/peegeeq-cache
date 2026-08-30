package dev.mars.peegeeq.cache.rest.server;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.Test;

/** Declares the product journey and management operations proven by a real browser test. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Test
@interface ManagementBrowserJourney {

    String value();

    String[] operations();
}
