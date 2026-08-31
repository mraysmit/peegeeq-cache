package dev.mars.peegeeq.cache.rest.server;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Validates one browser scenario against the catalogue's evidence contract. */
final class ManagementBrowserAccountability {

    private ManagementBrowserAccountability() {
    }

    static List<String> violations(
            Method method,
            Set<String> knownOperations,
            Set<String> databaseMutationOperations,
            Set<String> auditedOperations,
            Set<String> sensitiveOperations) {
        ManagementBrowserScenario scenario = method.getAnnotation(ManagementBrowserScenario.class);
        if (scenario == null) {
            return List.of("missing scenario metadata");
        }

        List<String> violations = new ArrayList<>();
        if (!scenario.id().matches("PW-[A-Z]+-[0-9]{3}")) {
            violations.add("invalid scenario ID");
        }
        requireText(scenario.requirement(), "missing source requirement", violations);
        requireText(scenario.action(), "missing browser action", violations);
        requireText(scenario.expectedResult(), "missing expected result", violations);
        requireText(scenario.cleanup(), "missing cleanup obligation", violations);

        Set<String> operations = new LinkedHashSet<>(Arrays.asList(scenario.operations()));
        Set<ManagementBrowserEvidence> evidence = new LinkedHashSet<>(Arrays.asList(scenario.evidence()));
        if (!evidence.contains(ManagementBrowserEvidence.VISIBLE_RESULT)) {
            violations.add("missing visible-result evidence");
        }
        if (!evidence.contains(ManagementBrowserEvidence.RESOURCE_CLEANUP)) {
            violations.add("missing cleanup evidence");
        }

        Set<String> unknownOperations = new LinkedHashSet<>(operations);
        unknownOperations.removeAll(knownOperations);
        if (!unknownOperations.isEmpty()) {
            violations.add("unknown operations " + unknownOperations);
        }
        if (!operations.isEmpty() && !evidence.contains(ManagementBrowserEvidence.HTTP_OPERATION)) {
            violations.add("operations declared without operation evidence");
        }
        if (intersects(operations, databaseMutationOperations)
                && !evidence.contains(ManagementBrowserEvidence.DATABASE)) {
            violations.add("database mutation lacks database evidence");
        }
        if (intersects(operations, auditedOperations)
                && !evidence.contains(ManagementBrowserEvidence.DURABLE_AUDIT)) {
            violations.add("audited operation lacks durable-audit evidence");
        }
        if (intersects(operations, sensitiveOperations)
                && !evidence.contains(ManagementBrowserEvidence.SENSITIVE_STATE)) {
            violations.add("sensitive operation lacks leakage evidence");
        }
        return List.copyOf(violations);
    }

    private static void requireText(String value, String violation, List<String> violations) {
        if (value.isBlank()) {
            violations.add(violation);
        }
    }

    private static <T> boolean intersects(Set<T> left, Set<T> right) {
        return left.stream().anyMatch(right::contains);
    }
}
