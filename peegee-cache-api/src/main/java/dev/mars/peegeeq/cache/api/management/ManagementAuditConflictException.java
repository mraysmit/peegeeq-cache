package dev.mars.peegeeq.cache.api.management;

/** Conflicting second terminal completion for one audit reservation. */
public final class ManagementAuditConflictException extends ManagementAuditException {
    public ManagementAuditConflictException(String message) { super(message); }
}
