package dev.mars.peegeeq.cache.api.management;

import dev.mars.peegeeq.cache.api.exception.CacheException;

/** Required security-audit failure, distinct from optional telemetry failure. */
public class ManagementAuditException extends CacheException {
    public ManagementAuditException(String message) { super(message); }
    public ManagementAuditException(String message, Throwable cause) { super(message, cause); }
}
