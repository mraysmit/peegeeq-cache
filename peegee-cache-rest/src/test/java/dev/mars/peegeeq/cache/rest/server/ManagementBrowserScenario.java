package dev.mars.peegeeq.cache.rest.server;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


/** Machine-readable accountability metadata for one distinct real-browser scenario. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@interface ManagementBrowserScenario {

    String id();

    String requirement();

    ManagementBrowserArea area();

    ManagementBrowserRisk risk();

    String action();

    String expectedResult();

    String cleanup();

    String[] operations() default {};

    ManagementBrowserEvidence[] evidence();
}

enum ManagementBrowserArea {
    AUTHENTICATION,
    SHELL,
    SETUP,
    OVERVIEW,
    NAMESPACE,
    ENTRY,
    COUNTER,
    LOCK,
    BACKEND,
    PUBSUB,
    MONITORING,
    HARDENING
}

enum ManagementBrowserRisk {
    CRITICAL,
    HIGH,
    MEDIUM
}

enum ManagementBrowserEvidence {
    VISIBLE_RESULT,
    HTTP_OPERATION,
    DATABASE,
    DURABLE_AUDIT,
    SENSITIVE_STATE,
    RESOURCE_CLEANUP
}
