package dev.mars.peegeeq.cache.api.management;

import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.CacheValue;
import dev.mars.peegeeq.cache.api.model.CounterTtlMode;
import dev.mars.peegeeq.cache.api.model.LockKey;
import dev.mars.peegeeq.cache.api.model.SetMode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagementRequestModelsTest {

    private static final CacheKey KEY = new CacheKey("customers", "42");

    @Test
    void entrySetAndTtlRequestsEnforceModeAndExactVersionRules() {
        ManagementCacheSetRequest request = new ManagementCacheSetRequest(
                KEY, CacheValue.ofString("value"), SetMode.ONLY_IF_VERSION_MATCHES, 4L,
                EntryTtlMode.REPLACE, Duration.ofMinutes(5));
        assertEquals(4, request.expectedVersion());

        assertThrows(IllegalArgumentException.class, () -> new ManagementCacheSetRequest(
                KEY, CacheValue.ofString("value"), SetMode.ONLY_IF_VERSION_MATCHES, null,
                EntryTtlMode.REMOVE, null));
        assertThrows(IllegalArgumentException.class, () -> new ManagementCacheSetRequest(
                KEY, CacheValue.ofString("value"), SetMode.UPSERT, 4L,
                EntryTtlMode.REMOVE, null));
        assertThrows(IllegalArgumentException.class,
                () -> new VersionedEntryTtlRequest(KEY, 4, Duration.ZERO));
    }

    @Test
    void revealDtosBindSensitiveDataToTheObservedVersionAndTime() {
        Instant revealedAt = Instant.parse("2026-08-20T06:00:00Z");
        RevealedEntryValue entry = new RevealedEntryValue(KEY, CacheValue.ofString("secret"), 9, revealedAt);
        RevealedLockOwner lock = new RevealedLockOwner(
                new LockKey("customers", "42"), "owner-token", 3, revealedAt);

        assertEquals(9, entry.version());
        assertEquals("owner-token", lock.ownerToken());
        assertThrows(IllegalArgumentException.class,
                () -> new RevealedEntryValue(KEY, CacheValue.ofString("secret"), -1, revealedAt));
    }

    @Test
    void counterSetAndAdjustRequestsRequireOneSupportedConcurrencyMode() {
        ManagementCounterSetRequest create = new ManagementCounterSetRequest(
                KEY, 10, null, true, CounterTtlMode.PRESERVE_EXISTING, null);
        ManagementCounterAdjustRequest adjust = new ManagementCounterAdjustRequest(
                KEY, -2, 8L, false, CounterTtlMode.REMOVE, null);

        assertTrue(create.requireAbsent());
        assertEquals(-2, adjust.delta());
        assertThrows(IllegalArgumentException.class, () -> new ManagementCounterSetRequest(
                KEY, 10, null, false, CounterTtlMode.PRESERVE_EXISTING, null));
        assertThrows(IllegalArgumentException.class, () -> new ManagementCounterAdjustRequest(
                KEY, 0, 8L, false, CounterTtlMode.PRESERVE_EXISTING, null));
    }

    @Test
    void bulkSelectionsAreBoundedAndUseExactlyOneSelectionMode() {
        EntryDeleteFilter filter = new EntryDeleteFilter("customers", "inactive:%", List.of());
        assertEquals("inactive:%", filter.prefix());
        assertTrue(filter.keys().isEmpty());

        assertThrows(IllegalArgumentException.class,
                () -> new EntryDeleteFilter("customers", "prefix", List.of(KEY)));
        assertThrows(IllegalArgumentException.class,
                () -> new EntryDeleteFilter("customers", null, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new ConfirmedEntryDelete("t".repeat(32), ""));
    }

    @Test
    void forceReleaseRequiresExactVersionKeyConfirmationAndBoundedReason() {
        ForceReleaseLockRequest request = new ForceReleaseLockRequest(
                new LockKey("locks", "resource-1"), 7, "resource-1", " operator intervention ");

        assertEquals(7, request.expectedVersion());
        assertEquals("operator intervention", request.reason());
        assertThrows(IllegalArgumentException.class, () -> new ForceReleaseLockRequest(
                new LockKey("locks", "resource-1"), 7, "other", null));
    }

    @Test
    void permissionAwareValuesNeverEncodeUnavailableAsZero() {
        AvailableValue<Long> unavailable = AvailableValue.unavailable("permission denied");

        assertEquals(Availability.UNAVAILABLE, unavailable.availability());
        assertNull(unavailable.value());
        assertThrows(IllegalArgumentException.class,
                () -> new AvailableValue<>(Availability.UNAVAILABLE, 0L, "permission denied"));
    }
}
