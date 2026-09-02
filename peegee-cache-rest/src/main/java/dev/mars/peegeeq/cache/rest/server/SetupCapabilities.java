package dev.mars.peegeeq.cache.rest.server;

import dev.mars.peegeeq.cache.api.management.AdminCapabilities;
import dev.mars.peegeeq.cache.api.management.ManagementCapability;

import java.nio.charset.StandardCharsets;
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
                new Features(true, true, true, true, true, true, true, true, true, true, true, true, true, true, true,
                        true, true, true, true),
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
                        capabilities.supports(ManagementCapability.ENTRY_INSPECTION),
                        capabilities.supports(ManagementCapability.EXPIRY_MONITORING),
                        capabilities.supports(ManagementCapability.ENTRY_MUTATION),
                        capabilities.supports(ManagementCapability.COUNTER_INSPECTION),
                        capabilities.supports(ManagementCapability.COUNTER_MUTATION),
                        capabilities.supports(ManagementCapability.LOCK_INSPECTION),
                        capabilities.supports(ManagementCapability.FORCE_LOCK_RELEASE),
                        bulkDelete,
                        bulkDelete,
                        true,
                        capabilities.supports(ManagementCapability.DATABASE_MONITORING),
                        capabilities.supports(ManagementCapability.ENTRY_REVEAL),
                        capabilities.supports(ManagementCapability.LOCK_REVEAL),
                        true,
                        true,
                        true,
                        true,
                        true),
                new Limits(49, 7_500, (int) maximumValueBytes));
    }

    public record Features(
            boolean namespaceInspection,
            boolean entryInspection,
            boolean expiredEntryInspection,
            boolean entryMutation,
            boolean counterInspection,
            boolean counterMutation,
            boolean lockInspection,
            boolean forcedLockRelease,
            boolean bulkEntryDelete,
            boolean bulkCounterDelete,
            boolean pubSub,
            boolean databaseStatistics,
            boolean entryValueReveal,
            boolean lockOwnerReveal,
            boolean pubSubPayloadReveal,
            boolean batchEntryOperations,
            boolean valueScan,
            boolean cacheMetrics,
            boolean ownerLockOperations) {

        public Features(
                boolean namespaceInspection,
                boolean entryInspection,
                boolean expiredEntryInspection,
                boolean entryMutation,
                boolean counterInspection,
                boolean counterMutation,
                boolean lockInspection,
                boolean forcedLockRelease,
                boolean bulkEntryDelete,
                boolean bulkCounterDelete,
                boolean pubSub,
                boolean databaseStatistics,
                boolean entryValueReveal,
                boolean lockOwnerReveal,
                boolean pubSubPayloadReveal) {
            this(namespaceInspection, entryInspection, expiredEntryInspection, entryMutation,
                    counterInspection, counterMutation, lockInspection, forcedLockRelease,
                    bulkEntryDelete, bulkCounterDelete, pubSub, databaseStatistics,
                    entryValueReveal, lockOwnerReveal, pubSubPayloadReveal,
                    true, true, true, true);
        }
    }

    public SetupCapabilities withPubSub(boolean enabled) {
        boolean effectivePubSub = features.pubSub() && enabled;
        boolean effectivePayloadReveal = features.pubSubPayloadReveal() && effectivePubSub;
        if (features.pubSub() == effectivePubSub
                && features.pubSubPayloadReveal() == effectivePayloadReveal) {
            return this;
        }
        return new SetupCapabilities(
                migrationVersion,
                new Features(
                        features.namespaceInspection(), features.entryInspection(),
                        features.expiredEntryInspection(), features.entryMutation(),
                        features.counterInspection(), features.counterMutation(),
                        features.lockInspection(), features.forcedLockRelease(),
                        features.bulkEntryDelete(), features.bulkCounterDelete(),
                        effectivePubSub, features.databaseStatistics(), features.entryValueReveal(),
                        features.lockOwnerReveal(), effectivePayloadReveal,
                        features.batchEntryOperations(), features.valueScan(),
                        features.cacheMetrics(), features.ownerLockOperations()),
                limits);
    }

    public SetupCapabilities withRuntimeConfiguration(SetupRuntimeConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        SetupCapabilities configured = withPubSub(configuration.pubSubEnabled());
        int prefixBytes = configuration.pubSubChannelPrefix().getBytes(StandardCharsets.UTF_8).length;
        int channelMaxBytes = 63 - prefixBytes - 2;
        if (configured.limits.pubSubChannelMaxBytes() == channelMaxBytes) {
            return configured;
        }
        return new SetupCapabilities(
                configured.migrationVersion,
                configured.features,
                new Limits(channelMaxBytes, configured.limits.pubSubPayloadMaxBytes(),
                        configured.limits.maximumValueBytes()));
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
