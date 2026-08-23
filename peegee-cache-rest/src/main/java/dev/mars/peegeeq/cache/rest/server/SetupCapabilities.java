package dev.mars.peegeeq.cache.rest.server;

import java.util.Objects;

public record SetupCapabilities(String migrationVersion, Features features, Limits limits) {
    public SetupCapabilities {
        migrationVersion = Objects.requireNonNull(migrationVersion, "migrationVersion");
        features = Objects.requireNonNull(features, "features");
        limits = Objects.requireNonNull(limits, "limits");
    }

    public static SetupCapabilities allSupported() {
        return new SetupCapabilities(
                "1",
                new Features(true, true, true, true, true, true, true, true, true, true),
                new Limits(49, 7_500, 10_485_760));
    }

    public record Features(
            boolean namespaceInspection,
            boolean expiredEntryInspection,
            boolean counterInspection,
            boolean lockInspection,
            boolean forcedLockRelease,
            boolean bulkEntryDelete,
            boolean bulkCounterDelete,
            boolean pubSub,
            boolean databaseStatistics,
            boolean sensitiveValueReveal) {
    }

    public record Limits(int pubSubChannelMaxBytes, int pubSubPayloadMaxBytes, int maximumValueBytes) {
        public Limits {
            if (pubSubChannelMaxBytes < 1 || pubSubChannelMaxBytes > 63
                    || pubSubPayloadMaxBytes < 1 || maximumValueBytes < 1) {
                throw new IllegalArgumentException("Capability limits must be positive and valid");
            }
        }
    }
}
