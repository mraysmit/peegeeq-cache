package dev.mars.peegeeq.cache.rest.server;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/** Reviewed operation IDs owned by the implemented M8 administration route families. */
public final class ManagementM8RouteInventory {

    private static final Set<String> OPERATION_IDS = operationIdSet();

    private ManagementM8RouteInventory() {
    }

    public static Set<String> operationIds() {
        return OPERATION_IDS;
    }

    private static Set<String> operationIdSet() {
        return Collections.unmodifiableSet(new TreeSet<>(Set.of(
                "revealEntryValue",
                "setEntry",
                "deleteEntry",
                "expireEntry",
                "persistEntry",
                "touchEntry",
                "previewEntryBulkDelete",
                "executeEntryBulkDelete",
                "setCounter",
                "adjustCounter",
                "expireCounter",
                "persistCounter",
                "deleteCounter",
                "previewCounterBulkDelete",
                "executeCounterBulkDelete",
                "revealLockOwner",
                "forceReleaseLock")));
    }
}
