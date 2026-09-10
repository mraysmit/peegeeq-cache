package dev.mars.peegeeq.cache.api.management;

import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.ValueType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagementMetadataModelsTest {

    @Test
    void activityModelsProvideBoundedImmutableProcessLocalPages() {
        ManagementActivityEvent event = new ManagementActivityEvent(
                "01K2VD6DA7G4EJ4CQG3ST6JN5M",
                Instant.parse("2026-08-23T09:30:00Z"),
                "alex.chen",
                "ENTRY_VALUE_REVEALED",
                ManagementAuditTerminalOutcome.SUCCEEDED,
                "orders",
                "logical-orders",
                new ManagementActivityResource(
                        ManagementActivityResourceType.CACHE_ENTRY, "customer:849203"),
                "Cache entry value revealed",
                "3dc1d62a-1617-4dd6-a219-c2c38a355817");
        ManagementActivityPage page = new ManagementActivityPage(
                List.of(event), event.eventId(), true);

        assertEquals(50, ManagementActivityQuery.defaults().limit());
        assertEquals("customer:849203", page.items().getFirst().resource().identifier());
        assertThrows(UnsupportedOperationException.class, () -> page.items().add(event));
        assertThrows(IllegalArgumentException.class,
                () -> new ManagementActivityQuery(null, 201, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ManagementActivityPage(List.of(event), null, true));
        assertThrows(IllegalArgumentException.class, () -> new ManagementActivityEvent(
                event.eventId(), event.occurredAt(), event.actor(), "x".repeat(129),
                event.outcome(), event.setupId(), event.namespace(), event.resource(),
                event.summary(), event.correlationId()));
    }

    @Test
    void runtimeMonitoringRetainsBoundedLowCardinalitySignals() {
        Instant observedAt = Instant.parse("2026-08-23T09:00:00Z");
        ManagementRuntimeMonitoring monitoring = new ManagementRuntimeMonitoring(
                observedAt,
                ManagementRuntimeLifecycleState.RUNNING,
                new ManagementRuntimePoolState(
                        AvailableValue.unavailable("pool metric unavailable"),
                        AvailableValue.unavailable("pool metric unavailable"),
                        AvailableValue.unavailable("pool metric unavailable"),
                        AvailableValue.available(10L)),
                1,
                2,
                3,
                4,
                8192,
                new ManagementAuditQueueState(0, 4096, true),
                new ManagementExpirySweeperState(true, true, observedAt),
                List.of(new ManagementOperationAggregate(
                        "getRuntimeMonitoring", ManagementOperationStatus.ACTIVE,
                        1, 0, 7)));

        assertEquals(ManagementRuntimeLifecycleState.RUNNING, monitoring.lifecycleState());
        assertEquals(Availability.UNAVAILABLE, monitoring.pool().active().availability());
        assertEquals("getRuntimeMonitoring", monitoring.operations().getFirst().operation());
        assertThrows(UnsupportedOperationException.class,
                () -> monitoring.operations().add(monitoring.operations().getFirst()));
        assertThrows(IllegalArgumentException.class, () -> new ManagementOperationAggregate(
                "resource:" + "x".repeat(128), ManagementOperationStatus.COMPLETE,
                1, 0, 0));
    }

    @Test
    void databaseMonitoringPreservesPermissionStateAndRejectsNegativeObservations() {
        Instant observedAt = Instant.parse("2026-08-23T08:00:00Z");
        ManagementDatabaseMonitoring monitoring = new ManagementDatabaseMonitoring(
                observedAt,
                AvailableValue.available(1024L),
                AvailableValue.available(512L),
                AvailableValue.available(1536L),
                AvailableValue.available(7L),
                AvailableValue.available(2L),
                AvailableValue.unavailable("statistics permission denied"),
                null,
                null,
                AvailableValue.unavailable("activity permission denied"),
                AvailableValue.available(3L),
                2,
                60_000L);

        assertEquals(Availability.UNAVAILABLE, monitoring.deadTuples().availability());
        assertEquals(null, monitoring.deadTuples().value());
        assertEquals(2, monitoring.expiryBacklog());
        assertThrows(IllegalArgumentException.class, () -> new ManagementDatabaseMonitoring(
                observedAt,
                AvailableValue.available(-1L),
                AvailableValue.available(0L),
                AvailableValue.available(0L),
                AvailableValue.available(0L),
                AvailableValue.available(0L),
                AvailableValue.available(0L),
                null,
                null,
                AvailableValue.available(0L),
                AvailableValue.available(0L),
                0,
                null));
    }

    @Test
    void overviewSnapshotRetainsBoundedDatabaseAggregatesAndAvailability() {
        Instant observedAt = Instant.parse("2026-08-23T08:00:00Z");
        NamespaceStats top = new NamespaceStats(
                "orders", 3, 2, 1, 1, 4, 128, observedAt);
        ManagementOverview overview = new ManagementOverview(
                observedAt,
                1,
                3,
                2,
                1,
                4,
                5,
                AvailableValue.unavailable("size permission denied"),
                AvailableValue.available(60_000L),
                Map.of(ValueType.STRING, 2L, ValueType.JSON, 1L),
                List.of(top));

        assertEquals(Availability.UNAVAILABLE, overview.schemaBytes().availability());
        assertEquals(5, overview.expiredCounterCount());
        assertEquals(2, overview.valueTypeCounts().get(ValueType.STRING));
        assertThrows(UnsupportedOperationException.class,
                () -> overview.topNamespaces().add(top));
        assertThrows(IllegalArgumentException.class, () -> new ManagementOverview(
                observedAt, -1, 0, 0, 0, 0, 0,
                AvailableValue.available(0L), AvailableValue.available(0L),
                Map.of(), List.of()));
    }

    @Test
    void metadataDtosCannotCarrySensitiveFields() {
        Set<String> forbidden = Set.of(
                "value", "payload", "ownertoken", "password", "credential", "secret", "authorization");

        for (Class<?> type : new Class<?>[]{ManagementEntryMetadata.class, ManagementLockMetadata.class,
                NamespaceStats.class, DatabaseStats.class, ExpiryStats.class,
                ManagementDatabaseMonitoring.class}) {
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
    void limitsExposeEffectiveDefaultsAndRejectInvalidValues() {
        ManagementLimits limits = ManagementLimits.defaults();

        assertEquals(200, limits.maximumPageSize());
        assertEquals(10L * 1024 * 1024, limits.maximumValueBytes());
        assertThrows(IllegalArgumentException.class,
                () -> new ManagementLimits(0, 1_000, 10_000, 500, 5));
    }

    @Test
    void entryMetadataCarriesOnlyObservableMetadata() {
        Instant now = Instant.parse("2026-08-20T06:00:00Z");
        ManagementEntryMetadata metadata = new ManagementEntryMetadata(
                new CacheKey("ns", "key"), ValueType.STRING, 12, 3,
                now, now, ManagementTtl.persistent());

        assertEquals(12, metadata.sizeBytes());
        assertEquals(3, metadata.version());
        assertFalse(Arrays.stream(metadata.getClass().getRecordComponents())
                .anyMatch(component -> component.getType().getSimpleName().equals("CacheValue")));
    }
}
