package dev.mars.peegeeq.cache.rest.server;

public record SetupConnectionTest(
        boolean databaseReachable,
        SetupSchemaState schemaState,
        String migrationVersion,
        long latencyMillis,
        SetupLimits limits) {
}
