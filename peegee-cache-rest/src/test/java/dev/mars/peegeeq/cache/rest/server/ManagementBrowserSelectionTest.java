package dev.mars.peegeeq.cache.rest.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ManagementBrowserSelectionTest {

    @BeforeEach
    @AfterEach
    void clearSelection() {
        System.clearProperty("peegeeq.playwright.scenarios");
    }

    @Test
    void retainsTheCompleteCatalogueWhenNoScenarioSelectionIsConfigured() {
        List<ManagementBrowserCase> catalogue = ManagementCapabilityBrowserIT.scenarios();

        assertEquals(catalogue, ManagementBrowserSelection.select(catalogue));
    }

    @Test
    void selectsOnlyExplicitCommaSeparatedScenarioIdsInCatalogueOrder() {
        System.setProperty("peegeeq.playwright.scenarios", "PW-CAPABILITY-010, PW-CAPABILITY-008");

        assertEquals(List.of("PW-CAPABILITY-008", "PW-CAPABILITY-010"),
                ManagementBrowserSelection.select(ManagementCapabilityBrowserIT.scenarios()).stream()
                        .map(ManagementBrowserCase::id)
                        .toList());
    }

    @Test
    void rejectsBlankUnknownAndMalformedScenarioSelections() {
        System.setProperty("peegeeq.playwright.scenarios", "PW-CAPABILITY-999");
        assertThrows(IllegalArgumentException.class,
                () -> ManagementBrowserSelection.select(ManagementCapabilityBrowserIT.scenarios()));

        System.setProperty("peegeeq.playwright.scenarios", "capability-8");
        assertThrows(IllegalArgumentException.class,
                () -> ManagementBrowserSelection.select(ManagementCapabilityBrowserIT.scenarios()));
    }
}
