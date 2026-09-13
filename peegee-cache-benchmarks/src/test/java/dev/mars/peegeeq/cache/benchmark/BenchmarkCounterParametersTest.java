package dev.mars.peegeeq.cache.benchmark;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BenchmarkCounterParametersTest {
    @Test
    void contentionSelectionAndEvidenceAreDeterministicAndBounded() {
        var parameters = new BenchmarkCounterParameters(100, 5, 0.9, 3, 10);
        for (long request = 0; request < 1_000; request++) {
            long index = parameters.counterIndex(71, request);
            assertTrue(index >= 0 && index < 100);
            assertEquals(index, parameters.counterIndex(71, request));
        }
        assertEquals("100", parameters.environment().get("scenario.counterCardinality"));
        assertEquals("5", parameters.environment().get("scenario.hotCounterCount"));
        assertEquals("0.9", parameters.environment().get("scenario.hotCounterRequestFraction"));
        assertEquals("3", parameters.environment().get("scenario.counterDelta"));
        assertEquals("10", parameters.environment().get("scenario.counterInitialValue"));
    }

    @Test
    void rejectsUnboundedOrMeaninglessCounterSettings() {
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkCounterParameters(0, 1, 1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkCounterParameters(10, 0, 1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkCounterParameters(10, 10, 1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkCounterParameters(10, 2, 0, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkCounterParameters(10, 2, 1, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new BenchmarkCounterParameters(10, 2, 1, Long.MAX_VALUE, 1));
    }
}
