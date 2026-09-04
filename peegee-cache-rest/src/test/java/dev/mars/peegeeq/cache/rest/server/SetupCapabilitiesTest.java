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

        SetupCapabilities.Features features = actual.features();
        assertEquals(true, features.namespaceInspection());
        assertEquals(true, features.entryInspection());
        assertEquals(false, features.expiredEntryInspection());
        assertEquals(true, features.entryMutation());
        assertEquals(true, features.counterInspection());
        assertEquals(true, features.counterMutation());
        assertEquals(true, features.lockInspection());
        assertEquals(false, features.forcedLockRelease());
        assertEquals(false, features.bulkEntryDelete());
        assertEquals(false, features.bulkCounterDelete());
        assertEquals(false, features.pubSub());
        assertEquals(true, features.databaseStatistics());
        assertEquals(false, features.entryValueReveal());
        assertEquals(false, features.lockOwnerReveal());
        assertEquals(false, features.pubSubPayloadReveal());
        assertEquals(false, features.batchEntryOperations());
        assertEquals(false, features.valueScan());
        assertEquals(false, features.cacheMetrics());
        assertEquals(false, features.ownerLockOperations());
        assertEquals(new SetupCapabilities.Limits(49, 7_500, 4_096), actual.limits());
    }

    @Test
    void reportsEntryAndLockRevealCapabilitiesIndependently() {
        AdminCapabilities entryReveal = new AdminCapabilities(
                EnumSet.of(ManagementCapability.ENTRY_REVEAL),
                ManagementLimits.defaults());
        AdminCapabilities lockReveal = new AdminCapabilities(
                EnumSet.of(ManagementCapability.LOCK_REVEAL),
                ManagementLimits.defaults());

        SetupCapabilities.Features entryFeatures = SetupCapabilities.from(entryReveal).features();
        assertEquals(true, entryFeatures.entryValueReveal());
        assertEquals(false, entryFeatures.lockOwnerReveal());
        assertEquals(false, entryFeatures.pubSubPayloadReveal());

        SetupCapabilities.Features lockFeatures = SetupCapabilities.from(lockReveal).features();
        assertEquals(false, lockFeatures.entryValueReveal());
        assertEquals(true, lockFeatures.lockOwnerReveal());
        assertEquals(false, lockFeatures.pubSubPayloadReveal());
    }

    @Test
    void mapsInspectionMutationAndRuntimePubSubCapabilitiesIndependently() {
        AdminCapabilities admin = new AdminCapabilities(
                EnumSet.of(
                        ManagementCapability.ENTRY_INSPECTION,
                        ManagementCapability.ENTRY_MUTATION,
                        ManagementCapability.COUNTER_MUTATION),
                ManagementLimits.defaults());

        SetupCapabilities.Features features = SetupCapabilities.from(
                new SetupCapabilities.Source(admin, true, false)).features();

        assertEquals(false, features.namespaceInspection());
        assertEquals(true, features.entryInspection());
        assertEquals(true, features.entryMutation());
        assertEquals(false, features.counterInspection());
        assertEquals(true, features.counterMutation());
        assertEquals(true, features.pubSub());
        assertEquals(false, features.pubSubPayloadReveal());
    }

    @Test
    void advertisesCacheOperationsOnlyWhenTheRuntimeProvidesTheCacheFacade() {
        AdminCapabilities admin = new AdminCapabilities(
                EnumSet.noneOf(ManagementCapability.class), ManagementLimits.defaults());

        SetupCapabilities.Features unavailable = SetupCapabilities.from(admin).features();
        assertEquals(false, unavailable.batchEntryOperations());
        assertEquals(false, unavailable.valueScan());
        assertEquals(false, unavailable.cacheMetrics());
        assertEquals(false, unavailable.ownerLockOperations());

        SetupCapabilities.Features available = SetupCapabilities.from(
                new SetupCapabilities.Source(
                        admin, false, false, true, true, true, true)).features();
        assertEquals(true, available.batchEntryOperations());
        assertEquals(true, available.valueScan());
        assertEquals(true, available.cacheMetrics());
        assertEquals(true, available.ownerLockOperations());
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
