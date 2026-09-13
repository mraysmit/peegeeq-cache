package dev.mars.peegeeq.cache.benchmark;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BenchmarkCapabilityInventoryTest {
    @Test
    void inventoryDistinguishesRunnableUnsupportedAndUnavailableObservations() {
        var inventory = BenchmarkCapabilityInventory.localPostgres();

        assertEquals(BenchmarkCapabilityInventory.Status.RUNNABLE,
                inventory.require("scenario.cache").status());
        assertEquals(BenchmarkCapabilityInventory.Status.RUNNABLE,
                inventory.require("scenario.scan").status());
        assertEquals(BenchmarkCapabilityInventory.Status.UNSUPPORTED,
                inventory.require("fault.ha-restart").status());
        assertEquals(BenchmarkCapabilityInventory.Status.OBSERVATION_UNAVAILABLE,
                inventory.require("diagnostic.database-cpu").status());
        assertTrue(inventory.environment().containsKey("capability.scenario.scan"));
        assertFalse(inventory.unavailableDiagnostics().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> inventory.require("unknown"));
    }
}
