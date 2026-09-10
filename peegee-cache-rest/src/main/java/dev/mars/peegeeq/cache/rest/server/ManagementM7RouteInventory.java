package dev.mars.peegeeq.cache.rest.server;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/** Reviewed operation IDs owned by the implemented M7 HTTP route families. */
public final class ManagementM7RouteInventory {

    private static final Set<String> OPERATION_IDS = operationIdSet();

    private ManagementM7RouteInventory() {
    }

    public static Set<String> operationIds() {
        return OPERATION_IDS;
    }

    private static Set<String> operationIdSet() {
        TreeSet<String> ids = new TreeSet<>();
        ids.addAll(Set.of(
                "getSession",
                "exchangeLocalToken",
                "deleteLocalSession"));
        ids.addAll(Set.of(
                "listSetups",
                "getSetup",
                "getSetupHealth"));
        ids.addAll(Set.of(
                "testUnregisteredSetup",
                "registerSetup",
                "connectSetup",
                "testRegisteredSetup",
                "detachSetup",
                "forgetSetup"));
        ids.addAll(Set.of(
                "getOverview",
                "getDatabaseMonitoring",
                "getRuntimeMonitoring",
                "listActivity",
                "listNamespaces",
                "exportNamespaces",
                "getNamespace",
                "listEntries",
                "getEntry",
                "listCounters",
                "getCounter",
                "listLocks",
                "getLock"));
        return Collections.unmodifiableSet(ids);
    }
}
