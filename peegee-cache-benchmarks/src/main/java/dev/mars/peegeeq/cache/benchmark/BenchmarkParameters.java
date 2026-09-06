package dev.mars.peegeeq.cache.benchmark;

import java.time.Duration;
import java.util.Objects;

/** Typed characterisation inputs; these do not change legacy regression configuration. */
public record BenchmarkParameters(LoadModel loadModel, int concurrency, int poolSize,
                                   double offeredPerSecond, int queueCapacity, Duration operationTimeout) {
    public enum LoadModel { CLOSED_LOOP, RATE_CONTROLLED }

    public BenchmarkParameters {
        Objects.requireNonNull(loadModel, "loadModel");
        if (concurrency <= 0 || poolSize <= 0 || queueCapacity < 0) {
            throw new IllegalArgumentException("Concurrency and pool must be positive; queue capacity must be non-negative");
        }
        validateRate(loadModel, offeredPerSecond);
        Objects.requireNonNull(operationTimeout, "operationTimeout");
        try {
            if (operationTimeout.toNanos() <= 0) {
                throw new IllegalArgumentException("Operation timeout must be positive");
            }
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Operation timeout exceeds nanosecond range", overflow);
        }
    }

    static void validateRate(LoadModel model, double rate) {
        if (!Double.isFinite(rate) || (model == LoadModel.CLOSED_LOOP ? rate != 0 : rate <= 0)) {
            throw new IllegalArgumentException("Offered rate must be finite, zero for closed loop and positive for rate control");
        }
    }
}
