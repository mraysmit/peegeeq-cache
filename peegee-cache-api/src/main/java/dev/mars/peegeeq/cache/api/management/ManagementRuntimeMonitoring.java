package dev.mars.peegeeq.cache.api.management;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Bounded process-local management runtime snapshot. */
public record ManagementRuntimeMonitoring(
        Instant observedAt,
        ManagementRuntimeLifecycleState lifecycleState,
        ManagementRuntimePoolState pool,
        long activeOperations,
        long pubSubSubscriptions,
        long sseClients,
        long webSocketClients,
        long retainedPayloadBytes,
        ManagementAuditQueueState auditQueue,
        ManagementExpirySweeperState expirySweeper,
        List<ManagementOperationAggregate> operations) {

    public ManagementRuntimeMonitoring {
        Objects.requireNonNull(observedAt, "observedAt");
        Objects.requireNonNull(lifecycleState, "lifecycleState");
        Objects.requireNonNull(pool, "pool");
        ManagementModelValidation.nonNegativeVersion(activeOperations, "activeOperations");
        ManagementModelValidation.nonNegativeVersion(pubSubSubscriptions, "pubSubSubscriptions");
        ManagementModelValidation.nonNegativeVersion(sseClients, "sseClients");
        ManagementModelValidation.nonNegativeVersion(webSocketClients, "webSocketClients");
        ManagementModelValidation.nonNegativeVersion(retainedPayloadBytes, "retainedPayloadBytes");
        Objects.requireNonNull(auditQueue, "auditQueue");
        Objects.requireNonNull(expirySweeper, "expirySweeper");
        operations = List.copyOf(Objects.requireNonNull(operations, "operations"));
        if (operations.size() > 256) {
            throw new IllegalArgumentException("operations must contain at most 256 items");
        }
    }
}
