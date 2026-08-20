package dev.mars.peegeeq.cache.api.management;

import io.vertx.core.Future;

/**
 * Fail-closed authoritative security-audit sink. A successful reservation durably
 * records intent and reserves exactly one terminal outcome slot. Implementations
 * must make repeated completion with the same outcome idempotent and reject conflicts.
 */
public interface ManagementAuditSink {
    Future<ManagementAuditReservation> reserveIntent(ManagementAuditIntent intent);
    Future<Void> complete(ManagementAuditReservation reservation, ManagementAuditOutcome outcome);
}
