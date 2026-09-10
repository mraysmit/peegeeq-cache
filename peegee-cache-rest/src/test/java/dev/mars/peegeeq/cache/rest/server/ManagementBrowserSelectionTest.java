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
        List<ManagementBrowserCase> catalogue = ManagementCounterBrowserIT.scenarios();

        assertEquals(catalogue, ManagementBrowserSelection.select(catalogue, ""));
    }

    @Test
    void selectsOnlyExplicitCommaSeparatedScenarioIdsInCatalogueOrder() {
        List<ManagementBrowserCase> catalogue = ManagementCounterBrowserIT.scenarios();
        String first = catalogue.get(0).id();
        String second = catalogue.get(1).id();
        String configured = second + ", " + first;

        assertEquals(List.of(first, second),
                ManagementBrowserSelection.select(catalogue, configured).stream()
                        .map(ManagementBrowserCase::id)
                        .toList());
        assertEquals(List.of(),
                ManagementBrowserSelection.select(
                        ManagementLockBrowserIT.scenarios(), configured));
        assertFalse(ManagementBrowserSelection.classHasRequestedScenario(
                ManagementLockBrowserIT.class, configured));
        assertTrue(ManagementBrowserSelection.classHasRequestedScenario(
                ManagementCounterBrowserIT.class, configured));
    }

    @Test
    void rejectsBlankUnknownAndMalformedScenarioSelections() {
        List<ManagementBrowserCase> catalogue = ManagementCounterBrowserIT.scenarios();

        assertThrows(IllegalArgumentException.class,
                () -> ManagementBrowserSelection.select(catalogue, "PW-COUNTER-999"));

        assertThrows(IllegalArgumentException.class,
                () -> ManagementBrowserSelection.select(catalogue, "counter-8"));
    }
}
