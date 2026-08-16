# Performance and resilience benchmarks

`peegee-cache-benchmarks` is an opt-in, executable PostgreSQL benchmark rather than a unit-test microbenchmark. It measures combined library and database behavior.

Run the default 30-second scenarios:

```shell
mvn -pl peegee-cache-benchmarks -am integration-test -Pbenchmark -DskipTests
```

For a short smoke run:

```shell
mvn -pl peegee-cache-benchmarks -am integration-test -Pbenchmark -DskipTests '-Dpeegeeq.benchmark.durationSeconds=2'
```

The harness reports operations, sustained throughput, and p50/p95/p99 latency for:

- concurrent SET+GET mixed workflows;
- concurrent increments against one contended counter;
- physical expiry lag with the runtime sweeper enabled;
- pool recovery after terminating all benchmark PostgreSQL backends.

Default acceptance thresholds are 50 operations/second, p99 at or below 1 second, expiry lag at or below 1 second, and pool recovery at or below 10 seconds. Override them with:

- `peegeeq.benchmark.concurrency`
- `peegeeq.benchmark.durationSeconds`
- `peegeeq.benchmark.minimumThroughput`
- `peegeeq.benchmark.maximumP99Millis`
- `peegeeq.benchmark.maximumExpiryLagMillis`
- `peegeeq.benchmark.maximumFailoverRecoveryMillis`

These defaults are regression smoke thresholds, not universal production SLOs. Record the hardware, PostgreSQL settings, network topology, dataset size, pool size, and JVM flags when publishing comparative results. Do not compare these local-container results to a remote Redis deployment as if the topology were equivalent.

PowerShell users should quote Maven `-D` arguments whose property names contain dots, as shown in the smoke command.
