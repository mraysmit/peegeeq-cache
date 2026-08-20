package dev.mars.peegeeq.cache.api.management;

import dev.mars.peegeeq.cache.api.exception.CacheException;

/** Optional telemetry adapter failure that must not satisfy or replace security auditing. */
public final class ManagementTelemetryException extends CacheException {
    public ManagementTelemetryException(String message) { super(message); }
    public ManagementTelemetryException(String message, Throwable cause) { super(message, cause); }
}
