package dev.mars.peegeeq.cache.benchmark;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Immutable weighted operation mix with deterministic seed/request selection. */
public final class BenchmarkWorkloadMix {
    public record Weight(String operation, int weight) {
        public Weight {
            Objects.requireNonNull(operation, "operation");
            if (operation.isBlank() || weight <= 0) {
                throw new IllegalArgumentException("Operation must be named and weight must be positive");
            }
        }
    }

    private final List<Weight> weights;
    private final List<String> operations;
    private final int totalWeight;

    public BenchmarkWorkloadMix(List<Weight> weights) {
        this.weights = List.copyOf(weights);
        if (this.weights.isEmpty()) throw new IllegalArgumentException("At least one operation weight is required");
        var names = new HashSet<String>();
        long total = 0;
        for (var item : this.weights) {
            if (!names.add(item.operation())) throw new IllegalArgumentException("Operation weights must be unique");
            total += item.weight();
            if (total > Integer.MAX_VALUE) throw new IllegalArgumentException("Total operation weight exceeds integer range");
        }
        totalWeight = (int) total;
        operations = this.weights.stream().map(Weight::operation).toList();
    }

    public List<Weight> weights() { return weights; }
    public List<String> operations() { return operations; }

    /** Selection is stable for the same seed and logical request ID, independent of completion order. */
    public String select(long seed, long requestId) {
        if (requestId < 0) throw new IllegalArgumentException("Request ID must not be negative");
        long slot = Long.remainderUnsigned(mix64(seed + requestId), totalWeight);
        long cumulative = 0;
        for (var item : weights) {
            cumulative += item.weight();
            if (slot < cumulative) return item.operation();
        }
        throw new IllegalStateException("Weighted operation selection exceeded its total");
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }
}
