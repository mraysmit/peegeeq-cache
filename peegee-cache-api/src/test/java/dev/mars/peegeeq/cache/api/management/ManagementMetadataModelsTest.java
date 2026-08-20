package dev.mars.peegeeq.cache.api.management;

import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.ValueType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagementMetadataModelsTest {

    @Test
    void metadataDtosCannotCarrySensitiveFields() {
        Set<String> forbidden = Set.of(
                "value", "payload", "ownertoken", "password", "credential", "secret", "authorization");

        for (Class<?> type : new Class<?>[]{ManagementEntryMetadata.class, ManagementLockMetadata.class,
                NamespaceStats.class, DatabaseStats.class, ExpiryStats.class}) {
            for (RecordComponent component : type.getRecordComponents()) {
                assertFalse(forbidden.contains(component.getName().toLowerCase()),
                        () -> type.getSimpleName() + " exposes " + component.getName());
            }
        }
    }

    @Test
    void ttlSnapshotsEnforceStateSpecificFields() {
        Instant expiry = Instant.parse("2026-08-20T07:00:00Z");
        ManagementTtl expiring = ManagementTtl.expiring(60_000, expiry);

        assertEquals(ManagementTtl.State.EXPIRING, expiring.state());
        assertThrows(IllegalArgumentException.class,
                () -> new ManagementTtl(ManagementTtl.State.PERSISTENT, 0L, expiry));
        assertThrows(IllegalArgumentException.class,
                () -> new ManagementTtl(ManagementTtl.State.EXPIRED, 1L, expiry));
    }

    @Test
    void allResourceQueriesEnforceTheSharedPaginationBounds() {
        assertThrows(IllegalArgumentException.class,
                () -> new EntryQuery("ns", null, null, null, EntryQuery.Sort.KEY_ASC, null, 500));
        assertThrows(IllegalArgumentException.class,
                () -> new CounterQuery(null, null, null, CounterQuery.Sort.KEY_ASC, null, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new LockQuery(null, null, null, null, 201));

        assertEquals(50, EntryQuery.defaults("ns").limit());
    }

    @Test
    void capabilitiesExposeEffectiveLimitsAndDefaultToUnsupported() {
        AdminCapabilities capabilities = AdminCapabilities.unsupported();

        assertTrue(capabilities.supported().isEmpty());
        assertFalse(capabilities.supports(ManagementCapability.ENTRY_REVEAL));
        assertEquals(200, capabilities.limits().maximumPageSize());
        assertThrows(IllegalArgumentException.class,
                () -> new ManagementLimits(0, 1_000, 10_000, 500, 5));
    }

    @Test
    void entryMetadataCarriesOnlyObservableMetadata() {
        Instant now = Instant.parse("2026-08-20T06:00:00Z");
        ManagementEntryMetadata metadata = new ManagementEntryMetadata(
                new CacheKey("ns", "key"), ValueType.STRING, 12, 3,
                now, now, now, ManagementTtl.persistent());

        assertEquals(12, metadata.sizeBytes());
        assertEquals(3, metadata.version());
        assertFalse(Arrays.stream(metadata.getClass().getRecordComponents())
                .anyMatch(component -> component.getType().getSimpleName().equals("CacheValue")));
    }
}
