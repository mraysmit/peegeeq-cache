package dev.mars.peegeeq.cache.benchmark;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

/** Runtime safety limits evaluated independently from performance outcomes. */
public record BenchmarkRuntimePolicy(long maximumHeapBytes, double maximumProcessCpu,
                                     Duration maximumEventLoopDelay, boolean requireDiagnostics) {
    public record Observation(Long heapBytes, Double processCpu, Duration eventLoopDelay) { }
    public record Evaluation(boolean allowed, List<String> reasons) {
        public Evaluation { reasons = List.copyOf(reasons); }
    }

    public BenchmarkRuntimePolicy {
        if (maximumHeapBytes <= 0 || !Double.isFinite(maximumProcessCpu)
                || maximumProcessCpu <= 0 || maximumProcessCpu > 1) {
            throw new IllegalArgumentException("Heap and process CPU limits must be finite and positive");
        }
        Objects.requireNonNull(maximumEventLoopDelay, "maximumEventLoopDelay");
        if (maximumEventLoopDelay.isZero() || maximumEventLoopDelay.isNegative()) {
            throw new IllegalArgumentException("Event-loop delay limit must be positive");
        }
    }

    public static BenchmarkRuntimePolicy fromProperties(Properties properties) {
        Objects.requireNonNull(properties, "properties");
        try {
            return new BenchmarkRuntimePolicy(
                    Long.parseLong(properties.getProperty("runtime.maximumHeapBytes", "536870912")),
                    Double.parseDouble(properties.getProperty("runtime.maximumProcessCpu", "0.95")),
                    Duration.parse(properties.getProperty("runtime.maximumEventLoopDelay", "PT0.25S")),
                    strictBoolean(properties.getProperty("runtime.requireDiagnostics", "false")));
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Runtime numeric controls are invalid", invalid);
        }
    }

    public Evaluation evaluate(Observation observation) {
        Objects.requireNonNull(observation, "observation");
        var reasons = new ArrayList<String>();
        compare(observation.heapBytes(), maximumHeapBytes, "heap bytes", reasons);
        if (observation.processCpu() == null) missing("process CPU", reasons);
        else if (!Double.isFinite(observation.processCpu()) || observation.processCpu() > maximumProcessCpu) {
            reasons.add("process CPU exceeded limit: " + observation.processCpu() + " > " + maximumProcessCpu);
        }
        if (observation.eventLoopDelay() == null) missing("event-loop delay", reasons);
        else if (observation.eventLoopDelay().compareTo(maximumEventLoopDelay) > 0) {
            reasons.add("event-loop delay exceeded limit: " + observation.eventLoopDelay());
        }
        return new Evaluation(reasons.isEmpty(), reasons);
    }

    private void compare(Long value, long maximum, String name, List<String> reasons) {
        if (value == null) missing(name, reasons);
        else if (value < 0 || value > maximum) reasons.add(name + " exceeded limit: " + value + " > " + maximum);
    }

    private void missing(String name, List<String> reasons) {
        if (requireDiagnostics) reasons.add(name + " diagnostic unavailable");
    }

    private static boolean strictBoolean(String value) {
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        throw new IllegalArgumentException("runtime.requireDiagnostics must be true or false");
    }
}
