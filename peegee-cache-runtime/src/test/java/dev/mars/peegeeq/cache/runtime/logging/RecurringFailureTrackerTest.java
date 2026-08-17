package dev.mars.peegeeq.cache.runtime.logging;

import org.junit.jupiter.api.Test;

import java.util.OptionalLong;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecurringFailureTrackerTest {

    @Test
    void emitsOnlyTheFirstFailureAndSummarizesRepeatsOnRecovery() {
        RecurringFailureTracker tracker = new RecurringFailureTracker();

        RecurringFailureTracker.Failure first = tracker.recordFailure();
        RecurringFailureTracker.Failure second = tracker.recordFailure();
        RecurringFailureTracker.Failure third = tracker.recordFailure();

        assertTrue(first.firstFailure());
        assertEquals(0, first.repeatedFailures());
        assertFalse(second.firstFailure());
        assertEquals(1, second.repeatedFailures());
        assertFalse(third.firstFailure());
        assertEquals(2, third.repeatedFailures());

        assertEquals(OptionalLong.of(2), tracker.recordRecovery());
        assertEquals(OptionalLong.empty(), tracker.recordRecovery());
        assertTrue(tracker.recordFailure().firstFailure());
    }

    @Test
    void countsConcurrentFailuresWithoutLosingTheFirstTransition() throws Exception {
        RecurringFailureTracker tracker = new RecurringFailureTracker();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(16)) {
            List<Future<RecurringFailureTracker.Failure>> observations = new ArrayList<>();
            for (int index = 0; index < 256; index++) {
                observations.add(executor.submit(() -> {
                    start.await();
                    return tracker.recordFailure();
                }));
            }

            start.countDown();
            long firstTransitions = 0;
            long maximumRepeat = 0;
            for (Future<RecurringFailureTracker.Failure> observation : observations) {
                RecurringFailureTracker.Failure failure = observation.get();
                firstTransitions += failure.firstFailure() ? 1 : 0;
                maximumRepeat = Math.max(maximumRepeat, failure.repeatedFailures());
            }

            assertEquals(1, firstTransitions);
            assertEquals(255, maximumRepeat);
            assertEquals(OptionalLong.of(255), tracker.recordRecovery());
        }
    }
}
