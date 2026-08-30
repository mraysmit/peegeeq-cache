package dev.mars.peegeeq.cache.rest.server;

import java.util.List;
import java.util.Set;

/** One independently executed and reported parameterized real-browser scenario. */
record ManagementBrowserCase(
        String id,
        String requirement,
        ManagementBrowserArea area,
        ManagementBrowserRisk risk,
        String action,
        String expectedResult,
        String cleanup,
        List<String> operations,
        Set<ManagementBrowserEvidence> evidence) {

    ManagementBrowserCase {
        operations = List.copyOf(operations);
        evidence = Set.copyOf(evidence);
    }

    @Override
    public String toString() {
        return id + " — " + action;
    }
}
