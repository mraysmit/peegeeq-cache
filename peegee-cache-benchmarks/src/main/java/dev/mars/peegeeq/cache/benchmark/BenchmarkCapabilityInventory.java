package dev.mars.peegeeq.cache.benchmark;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Versioned declaration of what a benchmark target can run and observe. */
public record BenchmarkCapabilityInventory(int version, Map<String, Capability> capabilities) {
    public enum Status { RUNNABLE, UNSUPPORTED, OBSERVATION_UNAVAILABLE }
    public record Capability(Status status, String detail) {
        public Capability {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(detail, "detail");
            if (detail.isBlank()) throw new IllegalArgumentException("Capability detail must not be blank");
        }
    }

    public BenchmarkCapabilityInventory {
        if (version != 1) throw new IllegalArgumentException("Unsupported capability inventory version");
        capabilities = Map.copyOf(capabilities);
        if (capabilities.isEmpty()) throw new IllegalArgumentException("Capabilities must not be empty");
    }

    public Capability require(String name) {
        var capability = capabilities.get(name);
        if (capability == null) throw new IllegalArgumentException("Unknown benchmark capability: " + name);
        return capability;
    }

    public Map<String, String> environment() {
        var values = new LinkedHashMap<String, String>();
        values.put("capability.inventory.version", Integer.toString(version));
        capabilities.forEach((name, capability) ->
                values.put("capability." + name, capability.status().name() + ": " + capability.detail()));
        return Map.copyOf(values);
    }

    public List<BenchmarkRunEvidence.Diagnostic> unavailableDiagnostics() {
        return capabilities.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("diagnostic.")
                        && entry.getValue().status() == Status.OBSERVATION_UNAVAILABLE)
                .map(entry -> new BenchmarkRunEvidence.Diagnostic(0, "INFO",
                        entry.getKey() + " unavailable: " + entry.getValue().detail()))
                .toList();
    }

    public static BenchmarkCapabilityInventory localPostgres() {
        var values = new LinkedHashMap<String, Capability>();
        for (String scenario : List.of("cache", "counter", "lock", "scan")) {
            values.put("scenario." + scenario, new Capability(Status.RUNNABLE,
                    "deterministic adapter verified against disposable PostgreSQL"));
        }
        values.put("scenario.pubsub", new Capability(Status.UNSUPPORTED,
                "managed characterisation adapter is not implemented"));
        values.put("scenario.expiry", new Capability(Status.UNSUPPORTED,
                "managed churn/lag adapter is not implemented"));
        values.put("fault.ha-restart", new Capability(Status.UNSUPPORTED,
                "requires an authorised external infrastructure procedure"));
        values.put("diagnostic.scheduler", new Capability(Status.RUNNABLE,
                "queue, in-flight, generator lag and attempt accounting are collected"));
        values.put("diagnostic.jvm", new Capability(Status.RUNNABLE,
                "heap, GC and process CPU are available through JVM management beans"));
        values.put("diagnostic.database-cpu", new Capability(Status.OBSERVATION_UNAVAILABLE,
                "no portable database-host CPU collector is configured"));
        values.put("diagnostic.storage", new Capability(Status.OBSERVATION_UNAVAILABLE,
                "no portable database storage-latency collector is configured"));
        return new BenchmarkCapabilityInventory(1, values);
    }
}
