package dev.mars.peegeeq.cache.rest.server;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/** Typed input for one self-contained Playwright evidence report. */
record ManagementBrowserEvidenceReport(
        String reportId,
        Instant startedAtUtc,
        Instant completedAtUtc,
        Environment environment,
        List<ScenarioResult> scenarios,
        Set<String> sensitiveCanaries) {

    ManagementBrowserEvidenceReport {
        scenarios = List.copyOf(scenarios);
        sensitiveCanaries = Set.copyOf(sensitiveCanaries);
    }

    record Environment(
            String javaVersion,
            String operatingSystem,
            String cpu,
            String memory,
            String chromiumVersion,
            String postgresVersion,
            String gitCommit) {
    }

    record ScenarioResult(
            String id,
            String name,
            ManagementBrowserArea area,
            ManagementBrowserRisk risk,
            String status,
            long durationMilliseconds,
            String requirement,
            List<String> observedOperations,
            Set<ManagementBrowserEvidence> evidence,
            String failure) {

        ScenarioResult {
            observedOperations = List.copyOf(observedOperations);
            evidence = Set.copyOf(evidence);
        }
    }
}
