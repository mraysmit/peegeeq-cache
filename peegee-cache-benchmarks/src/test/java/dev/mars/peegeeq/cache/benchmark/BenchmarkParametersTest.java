package dev.mars.peegeeq.cache.benchmark;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class BenchmarkParametersTest {
    @Test
    void allowsPoolPressureAndKeepsDeadlineIndependentOfRate() {
        var parameters = new BenchmarkParameters(BenchmarkParameters.LoadModel.RATE_CONTROLLED,
                32, 4, 1500.5, 64, Duration.ofMillis(750));
        assertEquals(4, parameters.poolSize());
        assertEquals(1500.5, parameters.offeredPerSecond());
        assertEquals(Duration.ofMillis(750), parameters.operationTimeout());
    }

    @Test
    void rejectsNonFiniteOrIncompatibleArrivalRates() {
        for (double rate : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1, 0}) {
            assertThrows(IllegalArgumentException.class, () -> parameters(
                    BenchmarkParameters.LoadModel.RATE_CONTROLLED, rate, 1, 1, 0, Duration.ofSeconds(1)));
        }
        assertThrows(IllegalArgumentException.class, () -> parameters(
                BenchmarkParameters.LoadModel.CLOSED_LOOP, 1, 1, 1, 0, Duration.ofSeconds(1)));
        assertEquals(0, parameters(BenchmarkParameters.LoadModel.CLOSED_LOOP,
                0, 1, 1, 0, Duration.ofSeconds(1)).offeredPerSecond());
    }

    @Test
    void rejectsInvalidResourceBoundsAndUnrepresentableDeadlines() {
        assertThrows(IllegalArgumentException.class, () -> parameters(
                BenchmarkParameters.LoadModel.CLOSED_LOOP, 0, 0, 1, 0, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> parameters(
                BenchmarkParameters.LoadModel.CLOSED_LOOP, 0, 1, 0, 0, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> parameters(
                BenchmarkParameters.LoadModel.CLOSED_LOOP, 0, 1, 1, -1, Duration.ofSeconds(1)));
        for (Duration timeout : new Duration[]{Duration.ZERO, Duration.ofNanos(-1), Duration.ofSeconds(Long.MAX_VALUE)}) {
            assertThrows(IllegalArgumentException.class, () -> parameters(
                    BenchmarkParameters.LoadModel.CLOSED_LOOP, 0, 1, 1, 0, timeout));
        }
    }

    private static BenchmarkParameters parameters(BenchmarkParameters.LoadModel model, double rate,
                                                   int concurrency, int pool, int queue, Duration timeout) {
        return new BenchmarkParameters(model, concurrency, pool, rate, queue, timeout);
    }
}
