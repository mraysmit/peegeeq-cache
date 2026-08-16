# Performance and resilience benchmarks

`peegee-cache-benchmarks` is an opt-in, executable PostgreSQL benchmark rather than a unit-test microbenchmark. It measures combined library and database behavior.

## Repeatable captured runs

The evidence-capture implementation is Java and uses the same Maven command on Windows and Linux. From the repository root, run:

```shell
mvn -pl peegee-cache-benchmarks -am integration-test -Pbenchmark-capture -DskipTests
```

The default command performs three independent 30-second benchmark repetitions with 8 foreground workers, a 12-connection pool, PostgreSQL 18.3, and the standard acceptance gates. It skips the ordinary test suite so test execution does not become an uncontrolled warm-up or thermal load. The Java runner receives typed results directly from `CacheBenchmarkMain`; it never parses Maven or console output to reconstruct measurements.

For release evidence from a clean checkout:

```shell
mvn verify
mvn -pl peegee-cache-benchmarks -am integration-test -Pbenchmark-capture -DskipTests \
  '-Dpeegeeq.benchmark.capture.runs=3' \
  '-Dpeegeeq.benchmark.durationSeconds=30' \
  '-Dpeegeeq.benchmark.capture.requireCleanGit=true' \
  '-Dpeegeeq.benchmark.capture.topology=Dedicated benchmark host; local Docker PostgreSQL; no competing workloads; performance power profile'
```

The backslashes above are shell line continuations; PowerShell users can put the command on one line or replace them with PowerShell backticks. The Maven properties and Java behavior are identical on both platforms.

For a short end-to-end capture smoke, run:

```shell
mvn -pl peegee-cache-benchmarks -am integration-test -Pbenchmark-capture -DskipTests '-Dpeegeeq.benchmark.capture.runs=1' '-Dpeegeeq.benchmark.durationSeconds=2'
```

Each invocation creates exactly one ignored evidence file: `benchmark-results/<UTC timestamp>-<Git commit>.html`. The self-contained report has no external assets and includes:

- benchmark status, timing, requested/completed/successful run counts, and acceptance gates;
- aggregate scenario throughput and latency across completed repetitions;
- full per-run operations, throughput, p50/p95/p99, telemetry overhead, expiry lag, and failover recovery;
- CPU, physical memory, disks, OS, Java/JVM, Maven, Docker resources, exact PostgreSQL image identity, Git state, topology, and benchmark configuration;
- expandable raw stdout/stderr for every run and the complete captured environment as embedded JSON.

The report is atomically replaced after each repetition, so completed evidence remains useful if a later repetition or final capture step fails.

The Java runner exits `0` only when every requested run completes and passes all benchmark gates. Its process code is `1` for a benchmark/capture failure and `2` when `peegeeq.benchmark.capture.requireCleanGit=true` rejects a dirty worktree before running. Maven reports either non-zero child code as a failed build, so automation launched through the documented Maven command should treat any non-zero Maven result as failure; the HTML header distinguishes failed execution from dirty-worktree rejection. Set `peegeeq.benchmark.capture.stopOnFailure=true` when later repetitions should not run after the first failure.

Capture configuration uses Java system properties:

- `peegeeq.benchmark.capture.runs` (default: 3)
- `peegeeq.benchmark.capture.outputRoot` (default: `benchmark-results`)
- `peegeeq.benchmark.capture.topology`
- `peegeeq.benchmark.capture.requireCleanGit` (default: false)
- `peegeeq.benchmark.capture.stopOnFailure` (default: false)
- `peegeeq.test.postgres.image` (default: `postgres:18.3-alpine`)

Workload and gate properties are listed below. The pool defaults to concurrency plus four, and any resolved pool size not greater than concurrency is rejected by the Java configuration before PostgreSQL starts.

The environment capture does not read Maven settings or credential stores. Common password/token/key assignments in captured option strings and credential-bearing HTTPS Git remotes are scrubbed. Review a report before publishing it because hostnames, storage models, paths, and repository status are intentionally recorded.

The typed result model, portable capture configuration, single-file HTML layout, atomic replacement, and HTML escaping have JUnit coverage and run with the ordinary module tests:

```shell
mvn -pl peegee-cache-benchmarks -am test
```

For meaningful comparisons, keep the Git commit or recorded dirty state, benchmark configuration, PostgreSQL image digest, topology, Docker CPU/memory allocation, power policy, and competing host workload equivalent. Use a clean worktree for release evidence, avoid unrelated workloads, and allow the host to reach a stable thermal state. The current harness provisions PostgreSQL through local Testcontainers; the topology description must not imply that it benchmarks a remote database. Local Docker results remain regression evidence, not production capacity commitments.

## Direct Maven execution

The benchmark can still be run without an HTML evidence report. Run the default 30-second scenarios:

```shell
mvn -pl peegee-cache-benchmarks -am integration-test -Pbenchmark -DskipTests
```

For a short smoke run:

```shell
mvn -pl peegee-cache-benchmarks -am integration-test -Pbenchmark -DskipTests '-Dpeegeeq.benchmark.durationSeconds=2'
```

The harness reports operations, sustained throughput, and p50/p95/p99 latency for:

- concurrent SET+GET mixed workflows;
- the same SET+GET workload with noop and Micrometer telemetry, including throughput and p99 overhead percentages;
- concurrent increments against one contended counter;
- concurrent acquisition/release attempts against one contended distributed lock;
- PostgreSQL publish-to-local-receive notification latency;
- physical expiry lag with the runtime sweeper enabled;
- pool recovery after terminating all benchmark PostgreSQL backends.

Default acceptance thresholds are 50 operations/second, p99 at or below 1 second, expiry lag at or below 1 second, and pool recovery at or below 10 seconds. Override them with:

- `peegeeq.benchmark.concurrency`
- `peegeeq.benchmark.poolSize` (default: concurrency + 4; must exceed concurrency)
- `peegeeq.benchmark.durationSeconds`
- `peegeeq.benchmark.minimumThroughput`
- `peegeeq.benchmark.maximumP99Millis`
- `peegeeq.benchmark.maximumExpiryLagMillis`
- `peegeeq.benchmark.maximumFailoverRecoveryMillis`
- `peegeeq.benchmark.maximumTelemetryOverheadPercent`

These defaults are regression smoke thresholds, not universal production SLOs. Record the hardware, PostgreSQL settings, network topology, dataset size, pool size, and JVM flags when publishing comparative results. Do not compare these local-container results to a remote Redis deployment as if the topology were equivalent.

PowerShell users should quote Maven `-D` arguments whose property names contain dots, as shown above. Prefer the Java capture profile for results intended to be compared or published.

## 2026-08-16 full-duration validation

The default benchmark was executed repeatedly on PostgreSQL 18.3. The local host used an Intel Core Ultra 9 185H (16 cores/22 logical processors), Windows 11, Java 25, and Docker Desktop 29.7.2 with 8 CPUs and approximately 46.7 GiB of memory allocated. PostgreSQL ran in the same Docker Desktop VM as the benchmark client, so this is a strong single-host regression baseline, not evidence for remote or managed-database network and storage behavior.

### Pre-fix diagnosis

Two of three identical executions aborted when an operation exceeded the harness's five-second timeout: pub/sub once and lock contention once. PostgreSQL activity sampling found no SQL statement or database lock wait lasting one second, while the client emitted repeated `Promise already completed` failures from the Vert.x SQL pool timeout/cancellation path. The benchmark pool size equalled its eight foreground workers and two workload managers independently ran 50 ms expiry sweepers against that same pool. This made client-side pool saturation and its timeout race the root cause; thresholds and operation timeouts were not relaxed.

The strict-TDD fix:

- makes pool capacity explicit and reserves four connections above foreground concurrency by default;
- rejects configurations where pool size does not exceed concurrency;
- disables expiry sweepers on the two sustained-workload managers;
- creates one short-lived, listener-free manager with one sweeper only for the expiry-lag scenario;
- reports and enforces each completed scenario immediately;
- identifies a failed or timed-out scenario while preserving its original cause;
- adds a real-PostgreSQL regression with four foreground workers and an aggressive sweeper, validated on PostgreSQL 15–18.

### Post-fix repeatability

Three identical default-duration executions then passed every unchanged acceptance gate. No operation timed out and no `Promise already completed` signature occurred.

| Run | Mixed noop throughput/s (p99 ms) | Mixed Micrometer throughput/s (p99 ms) | Counter throughput/s (p99 ms) | Lock throughput/s (p99 ms) | Pub/sub throughput/s (p99 ms) | Expiry lag ms | Failover ms |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 787.98 (11.407) | 787.95 (11.662) | 1,022.66 (21.362) | 1,298.51 (10.533) | 4,310.45 (3.803) | 20 | 13 |
| 2 | 972.59 (8.650) | 972.66 (8.352) | 1,111.74 (19.328) | 1,289.93 (10.321) | 4,190.92 (4.371) | 16 | 14 |
| 3 | 658.33 (15.409) | 658.40 (15.331) | 837.45 (30.628) | 1,172.06 (12.880) | 3,965.91 (4.788) | 28 | 16 |

The worst observed p99 was 30.628 ms against the 1,000 ms gate. Micrometer throughput overhead was 0.00% in all three runs; reported p99 overhead ranged from -3.44% to 2.23%, which is normal measurement variation for the interleaved comparison. The local repeatability gate is now passing. A benchmark on the intended production topology is still required before adopting these figures as capacity or SLO commitments.
