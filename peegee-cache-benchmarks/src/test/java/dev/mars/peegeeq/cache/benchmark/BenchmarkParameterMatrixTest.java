package dev.mars.peegeeq.cache.benchmark;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class BenchmarkParameterMatrixTest {
    @Test
    void expandsAxesInDeclaredOrderWithoutDroppingPoolPressureCombinations() {
        var matrix = matrix(List.of(4, 8), List.of(2, 6), List.of(100.0, 200.0));
        assertEquals(8, matrix.configurationCount());
        for (long index = 0; index < 8; index++) {
            var configuration = matrix.configurationAt(index);
            assertEquals(index < 4 ? 4 : 8, configuration.concurrency());
            assertEquals(index % 4 < 2 ? 2 : 6, configuration.poolSize());
            assertEquals(index % 2 == 0 ? 100.0 : 200.0, configuration.offeredPerSecond());
            assertEquals(10, configuration.queueCapacity());
            assertEquals(Duration.ofSeconds(2), configuration.operationTimeout());
        }
        assertThrows(IndexOutOfBoundsException.class, () -> matrix.configurationAt(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> matrix.configurationAt(8));
    }

    @Test
    void freezesExplicitAxisValuesAndRejectsDuplicatesRatherThanInflatingTheMatrix() {
        var concurrency = new ArrayList<>(List.of(2, 4));
        var matrix = matrix(concurrency, List.of(1), List.of(10.0));
        concurrency.clear();
        assertEquals(2, matrix.configurationCount());
        assertThrows(UnsupportedOperationException.class, () -> matrix.concurrencyValues().clear());
        assertThrows(IllegalArgumentException.class, () -> matrix(List.of(2, 2), List.of(1), List.of(10.0)));
        assertThrows(IllegalArgumentException.class, () -> matrix(List.of(2), List.of(1, 1), List.of(10.0)));
        assertThrows(IllegalArgumentException.class, () -> matrix(List.of(2), List.of(1), List.of(10.0, 10.0)));
    }

    @Test
    void signedZeroCannotDuplicateTheSameClosedLoopConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkParameterMatrix(
                BenchmarkParameters.LoadModel.CLOSED_LOOP, List.of(1), List.of(1), List.of(0.0, -0.0),
                0, Duration.ofSeconds(1)));
    }

    @Test
    void validatesEveryAxisBeforeAnyConfigurationIsResolved() {
        assertThrows(IllegalArgumentException.class, () -> matrix(List.of(), List.of(1), List.of(10.0)));
        assertThrows(IllegalArgumentException.class, () -> matrix(List.of(1), List.of(), List.of(10.0)));
        assertThrows(IllegalArgumentException.class, () -> matrix(List.of(1), List.of(1), List.of()));
        assertThrows(IllegalArgumentException.class, () -> matrix(List.of(1, 0), List.of(1), List.of(10.0)));
        assertThrows(IllegalArgumentException.class, () -> matrix(List.of(1), List.of(1, -1), List.of(10.0)));
        assertThrows(IllegalArgumentException.class, () -> matrix(List.of(1), List.of(1), List.of(10.0, Double.NaN)));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkParameterMatrix(
                BenchmarkParameters.LoadModel.CLOSED_LOOP, List.of(1), List.of(1), List.of(0.0, 10.0),
                0, Duration.ofSeconds(1)));
    }

    @Test
    void generatesBoundedAscendingAndDescendingIntegerRangesWithoutOverflow() {
        assertEquals(List.of(1, 3, 5), BenchmarkParameterMatrix.inclusiveRange(1, 6, 2, 3));
        assertEquals(List.of(5, 3, 1), BenchmarkParameterMatrix.inclusiveRange(5, 1, -2, 3));
        assertEquals(List.of(Integer.MAX_VALUE - 1, Integer.MAX_VALUE),
                BenchmarkParameterMatrix.inclusiveRange(Integer.MAX_VALUE - 1, Integer.MAX_VALUE, 1, 2));
        assertEquals(List.of(Integer.MIN_VALUE, -1),
                BenchmarkParameterMatrix.inclusiveRange(Integer.MIN_VALUE, 0, Integer.MAX_VALUE, 2));
    }

    @Test
    void rejectsOversizedOrInvalidRangesBeforeMaterialisingThem() {
        assertThrows(IllegalArgumentException.class, () -> BenchmarkParameterMatrix.inclusiveRange(1, 100, 1, 99));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkParameterMatrix.inclusiveRange(1, 5, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkParameterMatrix.inclusiveRange(1, 5, -1, 10));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkParameterMatrix.inclusiveRange(5, 1, 1, 10));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkParameterMatrix.inclusiveRange(1, 1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkParameterMatrix.inclusiveRange(
                Integer.MIN_VALUE, Integer.MAX_VALUE, 1, Integer.MAX_VALUE));
    }

    @Test
    void resolvesALargeProductLazily() {
        var values = IntStream.rangeClosed(1, 10_000).boxed().toList();
        var matrix = matrix(values, values, List.of(1.0, 2.0));
        assertEquals(200_000_000, matrix.configurationCount());
        var last = matrix.configurationAt(199_999_999);
        assertEquals(10_000, last.concurrency());
        assertEquals(10_000, last.poolSize());
        assertEquals(2.0, last.offeredPerSecond());
    }

    private static BenchmarkParameterMatrix matrix(List<Integer> concurrency, List<Integer> pools, List<Double> rates) {
        return new BenchmarkParameterMatrix(BenchmarkParameters.LoadModel.RATE_CONTROLLED,
                concurrency, pools, rates, 10, Duration.ofSeconds(2));
    }
}
