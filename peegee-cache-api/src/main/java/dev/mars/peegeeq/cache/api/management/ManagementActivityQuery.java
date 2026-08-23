package dev.mars.peegeeq.cache.api.management;

/** Bounded filters for recent process-local management activity. */
public record ManagementActivityQuery(
        String after,
        int limit,
        String namespace,
        String action,
        ManagementAuditTerminalOutcome outcome) {

    public ManagementActivityQuery {
        ManagementModelValidation.page(limit, after);
        after = after == null ? null
                : ManagementModelValidation.boundedText(after, "after", 1, 128, false);
        namespace = namespace == null ? null
                : ManagementModelValidation.boundedText(namespace, "namespace", 1, 128, false);
        action = action == null ? null
                : ManagementModelValidation.boundedText(action, "action", 1, 128, false);
    }

    public static ManagementActivityQuery defaults() {
        return new ManagementActivityQuery(null, 50, null, null, null);
    }
}
