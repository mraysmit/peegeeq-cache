package dev.mars.peegeeq.cache.api.management;

/** The operation may have committed but its authoritative terminal audit outcome did not. */
public final class ManagementAuditOutcomeException extends ManagementAuditException {

    public ManagementAuditOutcomeException(String message, Throwable cause) {
        super(message, cause);
    }
}
