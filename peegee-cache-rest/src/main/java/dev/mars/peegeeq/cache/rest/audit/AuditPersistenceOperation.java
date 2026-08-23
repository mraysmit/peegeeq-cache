package dev.mars.peegeeq.cache.rest.audit;

/** Bounded operation labels for durable management-audit persistence failures. */
public enum AuditPersistenceOperation {
    INTENT,
    OUTCOME,
    RECOVERY,
    SHUTDOWN
}
