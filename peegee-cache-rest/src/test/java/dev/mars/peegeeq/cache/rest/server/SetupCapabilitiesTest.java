package dev.mars.peegeeq.cache.rest.server;

import dev.mars.peegeeq.cache.api.management.AdminCapabilities;
import dev.mars.peegeeq.cache.api.management.ManagementCapability;
import dev.mars.peegeeq.cache.api.management.ManagementLimits;
import dev.mars.peegeeq.cache.runtime.bootstrap.SchemaBootstrapMode;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SetupCapabilitiesTest {

    @Test
    void derivesEveryUiFeatureAndEffectiveValueLimitFromTheManagementService() {
        AdminCapabilities admin = new AdminCapabilities(
                EnumSet.of(
                        ManagementCapability.NAMESPACE_INSPECTION,
                        ManagementCapability.ENTRY_INSPECTION,
                        ManagementCapability.ENTRY_MUTATION,
                        ManagementCapability.COUNTER_INSPECTION,
                        ManagementCapability.COUNTER_MUTATION,
                        ManagementCapability.LOCK_INSPECTION,
                        ManagementCapability.DATABASE_MONITORING),
                new ManagementLimits(75, 250, 4_096, 40, 3));

        SetupCapabilities actual = SetupCapabilities.from(admin);

        assertEquals(new SetupCapabilities(
                "1",
                new SetupCapabilities.Features(
                        true,
                        true,
                        false,
                        true,
                        true,
                        true,
                        true,
                        false,
                        false,
                        false,
                        true,
                        true,
                        false,
                        false,
                        true),
                new SetupCapabilities.Limits(49, 7_500, 4_096)), actual);
    }

    @Test
    void reportsEntryLockAndPubSubRevealCapabilitiesIndependently() {
        AdminCapabilities entryReveal = new AdminCapabilities(
                EnumSet.of(ManagementCapability.ENTRY_REVEAL),
                ManagementLimits.defaults());
        AdminCapabilities lockReveal = new AdminCapabilities(
                EnumSet.of(ManagementCapability.LOCK_REVEAL),
                ManagementLimits.defaults());

        SetupCapabilities.Features entryFeatures = SetupCapabilities.from(entryReveal).features();
        assertEquals(true, entryFeatures.entryValueReveal());
        assertEquals(false, entryFeatures.lockOwnerReveal());
        assertEquals(true, entryFeatures.pubSubPayloadReveal());

        SetupCapabilities.Features lockFeatures = SetupCapabilities.from(lockReveal).features();
        assertEquals(false, lockFeatures.entryValueReveal());
        assertEquals(true, lockFeatures.lockOwnerReveal());
        assertEquals(true, lockFeatures.pubSubPayloadReveal());
    }

    @Test
    void runtimePubSubEnablementCanOnlyNarrowUnderlyingCapabilities() {
        SetupCapabilities all = SetupCapabilities.allSupported();
        SetupCapabilities runtimeDisabled = all.withPubSub(false);
        assertEquals(false, runtimeDisabled.features().pubSub());
        assertEquals(false, runtimeDisabled.features().pubSubPayloadReveal());

        SetupCapabilities.Features source = all.features();
        SetupCapabilities backendDisabled = new SetupCapabilities(
                "1",
                new SetupCapabilities.Features(
                        source.namespaceInspection(), source.entryInspection(),
                        source.expiredEntryInspection(), source.entryMutation(),
                        source.counterInspection(), source.counterMutation(),
                        source.lockInspection(), source.forcedLockRelease(),
                        source.bulkEntryDelete(), source.bulkCounterDelete(),
                        false, source.databaseStatistics(), source.entryValueReveal(),
                        source.lockOwnerReveal(), false,
                        source.batchEntryOperations(), source.valueScan(),
                        source.cacheMetrics(), source.ownerLockOperations()),
                all.limits());
        SetupCapabilities runtimeEnabled = backendDisabled.withPubSub(true);
        assertEquals(false, runtimeEnabled.features().pubSub());
        assertEquals(false, runtimeEnabled.features().pubSubPayloadReveal());
    }

    @Test
    void runtimePubSubPrefixDeterminesTheAdvertisedUsableChannelBytes() {
        SetupRuntimeConfiguration configuration = new SetupRuntimeConfiguration(
                60_000L, true, 30_000, 500,
                false, 50, 10_000, 1_000, 3, 30_000,
                "browser_cache", true,
                SchemaBootstrapMode.APPLY,
                SetupRuntimeConfiguration.TelemetryMode.NOOP);

        SetupCapabilities configured = SetupCapabilities.allSupported()
                .withRuntimeConfiguration(configuration);

        assertEquals(48, configured.limits().pubSubChannelMaxBytes());
        assertEquals(true, configured.features().pubSub());
    }
}
