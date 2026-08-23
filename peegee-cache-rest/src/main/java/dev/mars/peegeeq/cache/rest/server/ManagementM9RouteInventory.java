package dev.mars.peegeeq.cache.rest.server;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/** Reviewed operation IDs owned by the implemented M9 live-transport route families. */
public final class ManagementM9RouteInventory {

    private static final Set<String> OPERATION_IDS = Collections.unmodifiableSet(
            new TreeSet<>(Set.of(
                    "createPubSubSubscription",
                    "streamPubSubMessages",
                    "revealPubSubPayload",
                    "deletePubSubSubscription",
                    "publishPubSubMessage",
                    "streamMetrics",
                    "monitoringWebSocket")));

    private ManagementM9RouteInventory() {
    }

    public static Set<String> operationIds() {
        return OPERATION_IDS;
    }
}
