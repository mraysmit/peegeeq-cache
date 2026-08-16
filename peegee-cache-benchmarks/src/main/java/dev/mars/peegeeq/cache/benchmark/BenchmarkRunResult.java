package dev.mars.peegeeq.cache.benchmark;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Complete typed output from one execution of the PostgreSQL benchmark. */
public record BenchmarkRunResult(BenchmarkConfig config, List<BenchmarkScenarioResult> scenarios,
                                 BenchmarkTelemetryResult telemetry, Duration expiryLag,
                                 Duration poolFailoverRecovery) {

    public static final List<String> REQUIRED_SCENARIOS = List.of(
            "mixed-set-get-noop-telemetry",
            "mixed-set-get-micrometer",
            "counter-contention",
            "lock-contention",
            "pubsub-publish-to-receive");

    public BenchmarkRunResult {
        Objects.requireNonNull(config, "config");
        scenarios = List.copyOf(scenarios);
        Objects.requireNonNull(telemetry, "telemetry");
        Objects.requireNonNull(expiryLag, "expiryLag");
        Objects.requireNonNull(poolFailoverRecovery, "poolFailoverRecovery");
        Set<String> names = scenarios.stream().map(BenchmarkScenarioResult::name).collect(Collectors.toSet());
        if (scenarios.size() != REQUIRED_SCENARIOS.size() || !names.containsAll(REQUIRED_SCENARIOS)) {
            throw new IllegalArgumentException("A benchmark result must contain each required scenario exactly once");
        }
        if (expiryLag.isNegative() || poolFailoverRecovery.isNegative()) {
            throw new IllegalArgumentException("Recovery and expiry durations must be non-negative");
        }
    }

    Map<String, Object> toMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("configuration", config.toMap());
        value.put("scenarios", scenarios.stream().map(BenchmarkScenarioResult::toMap).toList());
        value.put("telemetry", telemetry.toMap());
        value.put("expiryLagMilliseconds", expiryLag.toMillis());
        value.put("poolFailoverRecoveryMilliseconds", poolFailoverRecovery.toMillis());
        return value;
    }
}
