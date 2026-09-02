package dev.mars.peegeeq.cache.rest.server;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagementBrowserSelectionTest {

    @Test
    void retainsTheCompleteCatalogueWhenNoScenarioSelectionIsConfigured() {
        List<ManagementBrowserCase> catalogue = ManagementCapabilityBrowserIT.scenarios();

        assertEquals(catalogue, ManagementBrowserSelection.select(catalogue, ""));
    }

    @Test
    void selectsOnlyExplicitCommaSeparatedScenarioIdsInCatalogueOrder() {
        String configured = "PW-CAPABILITY-010, PW-CAPABILITY-008";

        assertEquals(List.of("PW-CAPABILITY-008", "PW-CAPABILITY-010"),
                ManagementBrowserSelection.select(
                                ManagementCapabilityBrowserIT.scenarios(), configured).stream()
                        .map(ManagementBrowserCase::id)
                        .toList());
        assertEquals(List.of(),
                ManagementBrowserSelection.select(
                        ManagementCounterBrowserIT.scenarios(), configured));
        assertFalse(ManagementBrowserSelection.classHasRequestedScenario(
                ManagementCounterBrowserIT.class, configured));
        assertTrue(ManagementBrowserSelection.classHasRequestedScenario(
                ManagementCapabilityBrowserIT.class, configured));
    }

    @Test
    void rejectsBlankUnknownAndMalformedScenarioSelections() {
        assertThrows(IllegalArgumentException.class,
                () -> ManagementBrowserSelection.select(
                        ManagementCapabilityBrowserIT.scenarios(), "PW-CAPABILITY-999"));

        assertThrows(IllegalArgumentException.class,
                () -> ManagementBrowserSelection.select(
                        ManagementCapabilityBrowserIT.scenarios(), "capability-8"));
    }
}
