package dev.mars.peegeeq.cache.rest.server;

import dev.mars.peegeeq.cache.api.management.AdminCapabilities;
import dev.mars.peegeeq.cache.api.management.ManagementCapability;

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
                new Features(true, true, true, true, true, true, true, true, true, true, true, true),
                new Limits(49, 7_500, 10_485_760));
    }

    public static SetupCapabilities from(AdminCapabilities capabilities) {
        Objects.requireNonNull(capabilities, "capabilities");
        boolean bulkDelete = capabilities.supports(ManagementCapability.BULK_DELETE);
        long maximumValueBytes = capabilities.limits().maximumValueBytes();
        if (maximumValueBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("maximumValueBytes exceeds the management API integer limit");
        }
        return new SetupCapabilities(
                "1",
                new Features(
                        capabilities.supports(ManagementCapability.NAMESPACE_INSPECTION),
                        capabilities.supports(ManagementCapability.EXPIRY_MONITORING),
                        capabilities.supports(ManagementCapability.COUNTER_INSPECTION),
                        capabilities.supports(ManagementCapability.LOCK_INSPECTION),
                        capabilities.supports(ManagementCapability.FORCE_LOCK_RELEASE),
                        bulkDelete,
                        bulkDelete,
                        true,
                        capabilities.supports(ManagementCapability.DATABASE_MONITORING),
                        capabilities.supports(ManagementCapability.ENTRY_REVEAL),
                        capabilities.supports(ManagementCapability.LOCK_REVEAL),
                        true),
                new Limits(49, 7_500, (int) maximumValueBytes));
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
            boolean entryValueReveal,
            boolean lockOwnerReveal,
            boolean pubSubPayloadReveal) {
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
