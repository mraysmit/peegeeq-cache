package dev.mars.peegeeq.cache.rest.server;

import dev.mars.peegeeq.cache.api.management.AdminCapabilities;
import dev.mars.peegeeq.cache.api.management.ManagementCapability;
import dev.mars.peegeeq.cache.api.management.ManagementLimits;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SetupCapabilitiesTest {

    @Test
    void derivesEveryUiFeatureAndEffectiveValueLimitFromTheManagementService() {
        AdminCapabilities admin = new AdminCapabilities(
                EnumSet.of(
                        ManagementCapability.NAMESPACE_INSPECTION,
                        ManagementCapability.COUNTER_INSPECTION,
                        ManagementCapability.LOCK_INSPECTION,
                        ManagementCapability.DATABASE_MONITORING),
                new ManagementLimits(75, 250, 4_096, 40, 3));

        SetupCapabilities actual = SetupCapabilities.from(admin);

        assertEquals(new SetupCapabilities(
                "1",
                new SetupCapabilities.Features(
                        true,
                        false,
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
}
