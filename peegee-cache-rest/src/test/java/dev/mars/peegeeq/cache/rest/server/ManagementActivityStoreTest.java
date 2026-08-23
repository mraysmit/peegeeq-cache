package dev.mars.peegeeq.cache.rest.server;

import dev.mars.peegeeq.cache.api.management.ManagementActivityEvent;
import dev.mars.peegeeq.cache.api.management.ManagementActivityQuery;
import dev.mars.peegeeq.cache.api.management.ManagementActivityResource;
import dev.mars.peegeeq.cache.api.management.ManagementActivityResourceType;
import dev.mars.peegeeq.cache.api.management.ManagementAuditTerminalOutcome;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagementActivityStoreTest {

    @Test
    void listenersReceiveCommittedProcessLocalActivityAndCanDetach() {
        ManagementActivityStore store = new ManagementActivityStore(4);
        List<String> observed = new ArrayList<>();
        AutoCloseable attachment = store.listen(event -> observed.add(event.eventId()));

        store.publish(event("01", "orders", "one", "ENTRY_SET",
                ManagementAuditTerminalOutcome.SUCCEEDED));
        assertEquals(List.of("01"), observed);

        assertDoesNotThrow(attachment::close);
        store.publish(event("02", "orders", "one", "ENTRY_SET",
                ManagementAuditTerminalOutcome.SUCCEEDED));
        assertEquals(List.of("01"), observed);
    }

    @Test
    void retainsBoundedNewestFirstActivityWithExclusiveKeysetFilters() {
        ManagementActivityStore store = new ManagementActivityStore(4);
        store.publish(event("01", "orders", "one", "ENTRY_SET",
                ManagementAuditTerminalOutcome.SUCCEEDED));
        store.publish(event("02", "orders", "two", "ENTRY_SET",
                ManagementAuditTerminalOutcome.FAILED));
        store.publish(event("03", "billing", "one", "COUNTER_SET",
                ManagementAuditTerminalOutcome.SUCCEEDED));
        store.publish(event("04", "orders", "one", "ENTRY_SET",
                ManagementAuditTerminalOutcome.SUCCEEDED));
        store.publish(event("05", "orders", "one", "ENTRY_SET",
                ManagementAuditTerminalOutcome.SUCCEEDED));

        var first = store.recent("orders", new ManagementActivityQuery(
                null, 1, "one", "ENTRY_SET", ManagementAuditTerminalOutcome.SUCCEEDED));
        assertEquals("05", first.items().getFirst().eventId());
        assertTrue(first.hasMore());
        assertEquals("05", first.nextAfter());

        var second = store.recent("orders", new ManagementActivityQuery(
                first.nextAfter(), 1, "one", "ENTRY_SET", ManagementAuditTerminalOutcome.SUCCEEDED));
        assertEquals("04", second.items().getFirst().eventId());
        assertFalse(second.hasMore());
        var exhausted = store.recent("orders", new ManagementActivityQuery(
                "04", 1, "one", "ENTRY_SET", ManagementAuditTerminalOutcome.SUCCEEDED));
        assertTrue(exhausted.items().isEmpty(), "event 01 must have been evicted at capacity");

        var failed = store.recent("orders", new ManagementActivityQuery(
                null, 50, null, null, ManagementAuditTerminalOutcome.FAILED));
        assertEquals("02", failed.items().getFirst().eventId());
        assertThrows(IllegalArgumentException.class,
                () -> store.publish(event("05", "orders", "one", "ENTRY_SET",
                        ManagementAuditTerminalOutcome.SUCCEEDED)));
        assertThrows(IllegalArgumentException.class,
                () -> store.recent("orders", new ManagementActivityQuery(
                        "evicted-or-unknown", 50, null, null, null)));
    }

    private static ManagementActivityEvent event(
            String id,
            String setupId,
            String namespace,
            String action,
            ManagementAuditTerminalOutcome outcome) {
        return new ManagementActivityEvent(
                id,
                Instant.parse("2026-08-23T10:00:00Z").plusSeconds(Integer.parseInt(id)),
                "operator",
                action,
                outcome,
                setupId,
                namespace,
                new ManagementActivityResource(ManagementActivityResourceType.CACHE_ENTRY, id),
                "bounded activity",
                "correlation-" + id);
    }
}
