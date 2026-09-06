package dev.mars.peegeeq.cache.benchmark;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Finite Cartesian specification of independent load axes. Values retain declaration order;
 * rates vary fastest, then pools, then concurrency. Resolution does not allocate the product.
 * Forks, repetitions and execution budgets belong to {@link BenchmarkExperiment}.
 */
public final class BenchmarkParameterMatrix {
    private final BenchmarkParameters.LoadModel loadModel;
    private final List<Integer> concurrencyValues;
    private final List<Integer> poolSizes;
    private final List<Double> offeredRates;
    private final int queueCapacity;
    private final Duration operationTimeout;
    private final long configurationCount;

    public BenchmarkParameterMatrix(BenchmarkParameters.LoadModel loadModel, List<Integer> concurrencyValues,
                                     List<Integer> poolSizes, List<Double> offeredRates,
                                     int queueCapacity, Duration operationTimeout) {
        this.loadModel = Objects.requireNonNull(loadModel, "loadModel");
        this.concurrencyValues = axis(concurrencyValues, "concurrency");
        this.poolSizes = axis(poolSizes, "pool size");
        this.offeredRates = axis(offeredRates, "offered rate");
        this.queueCapacity = queueCapacity;
        this.operationTimeout = operationTimeout;
        for (int concurrency : this.concurrencyValues) {
            if (concurrency <= 0) throw new IllegalArgumentException("Concurrency values must be positive");
        }
        for (int poolSize : this.poolSizes) {
            if (poolSize <= 0) throw new IllegalArgumentException("Pool sizes must be positive");
        }
        var distinctRates = new HashSet<Double>();
        for (double rate : this.offeredRates) {
            BenchmarkParameters.validateRate(loadModel, rate);
            // IEEE signed zeros express the same closed-loop rate; retain input but reject duplicates.
            if (!distinctRates.add(rate == 0 ? 0.0 : rate)) {
                throw new IllegalArgumentException("Offered rates must describe distinct configurations");
            }
        }
        // All axes are independent in this schema. Validate the shared resource/deadline fields once.
        new BenchmarkParameters(loadModel, this.concurrencyValues.getFirst(), this.poolSizes.getFirst(),
                this.offeredRates.getFirst(), queueCapacity, operationTimeout);
        try {
            configurationCount = Math.multiplyExact(Math.multiplyExact((long) this.concurrencyValues.size(),
                    this.poolSizes.size()), this.offeredRates.size());
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Parameter matrix exceeds configuration count range", overflow);
        }
    }

    public List<Integer> concurrencyValues() { return concurrencyValues; }
    public List<Integer> poolSizes() { return poolSizes; }
    public List<Double> offeredRates() { return offeredRates; }
    public long configurationCount() { return configurationCount; }

    public BenchmarkParameters configurationAt(long index) {
        if (index < 0 || index >= configurationCount) throw new IndexOutOfBoundsException("Configuration index: " + index);
        int rateIndex = (int) (index % offeredRates.size());
        long remaining = index / offeredRates.size();
        int poolIndex = (int) (remaining % poolSizes.size());
        int concurrencyIndex = (int) (remaining / poolSizes.size());
        return new BenchmarkParameters(loadModel, concurrencyValues.get(concurrencyIndex), poolSizes.get(poolIndex),
                offeredRates.get(rateIndex), queueCapacity, operationTimeout);
    }

    /** Inclusive bound, not a forced endpoint: 1..6 by 2 yields 1, 3, 5. */
    public static List<Integer> inclusiveRange(int first, int last, int step, int maximumValues) {
        if (step == 0 || maximumValues <= 0 || (first < last && step < 0) || (first > last && step > 0)) {
            throw new IllegalArgumentException("Range direction, step and value budget must be valid");
        }
        long count = Math.abs((long) last - first) / Math.abs((long) step) + 1;
        if (count > maximumValues) throw new IllegalArgumentException("Range exceeds value budget: " + count);
        var values = new ArrayList<Integer>((int) count);
        for (long index = 0; index < count; index++) values.add((int) (first + index * step));
        return List.copyOf(values);
    }

    private static <T> List<T> axis(List<T> values, String name) {
        var frozen = List.copyOf(values);
        if (frozen.isEmpty() || new HashSet<>(frozen).size() != frozen.size()) {
            throw new IllegalArgumentException(name + " axis must be non-empty and contain distinct values");
        }
        return frozen;
    }
}
