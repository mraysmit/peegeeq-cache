# Performance and resilience benchmarks

`peegee-cache-benchmarks` is an opt-in, executable PostgreSQL benchmark rather than a unit-test microbenchmark. It measures combined library and database behavior.

For planned parameterised workload/timeframe experiments, interval statistics, performance trends
and degradation-onset/recovery analysis, see the
[performance characterisation plan](design/PEEGEEQ_CACHE_PRODUCTION_BENCHMARK_PLAN.md).
The parameterised characterisation framework is implemented end to end: immutable finite
specification, closed-loop/rate-controlled scheduling, phase/workload transitions, managed retries,
real cache/counter/lock/scan adapters, bounded interval recording, atomic checkpoints, versioned
trend analysis, explicit target/lifecycle orchestration and campaign review. The legacy regression
profiles remain separate and unchanged in meaning.

**Current status (audited 15 September 2026 at `f983355`):** B0–B5 framework implementation is
complete and B6 is complete for the retained six-run local framework-acceptance campaign. This is
framework/regression evidence only; no external or production-capacity campaign has been run.

Each characterisation execution has one authoritative versioned JSON file. A self-contained HTML
view is derived from the final JSON and records its SHA-256. The JSON retains resolved configuration,
counters/rates, scalar metrics, diagnostics and six bounded latency distributions. Analysis reports
persistent latency/reliability/pressure findings, isolated spikes, recovery and inconclusive evidence.
Capabilities and unavailable diagnostics are explicit; absent observations are never fabricated as zero.

The incremental `BenchmarkCheckpointWriter` accepts only **new** measurements and diagnostics in
each batch; do not pass cumulative history as with the older full-snapshot `BenchmarkRunJsonWriter`.
Its limits bound batch items, encoded batch bytes and distinct scenarios. Prior measurement/diagnostic
history stays on disk, copied through an 8 KiB buffer into an atomically replaced JSON file. This
bounds retained history memory, not total disk space or write cost: each publication copies the growing
run and needs roughly one extra run-sized temporary file. There is no non-atomic fallback or permanent
sidecar result file. The older snapshot API remains available but is not the long-run pipeline.

`BenchmarkCheckpointPipeline` uses a caller-owned Vert.x worker and limits pending batch count and
encoded bytes, including the batch being written. Successful completion means publication. Queue
rejection leaves the batch with the producer for explicit retry or stop; a write failure fails all
pending futures and stops admission. Submit terminal status explicitly; `close()` drains accepted work
but does not invent completion or close the worker. Close the pipeline before closing its worker.
After a process interruption or persistence failure, the last published file can still say `RUNNING`.

`BenchmarkCheckpointSession.open(...)` now supplies automatic publication cadence around that
pipeline. Pass an empty `RUNNING` manifest, explicit writer limits, a whole-millisecond maximum
delay and an injected UTC clock. Resolved policy is recorded in reserved `checkpoint.*` environment
fields. Append already-recorded measurements or diagnostics: the session flushes at the item limit
or when the oldest staged item's timer fires. It does not roll over the recorder or generate load.
There is at most one active batch plus one bounded staging batch. Slow storage can delay publication;
the configured delay is a flush trigger, not a guaranteed persistence deadline. A full staging batch
or oversized candidate rejects explicitly, leaving the producer responsible for retry or stop.

Item futures complete after publication. Await `finish(COMPLETED/FAILED/STOPPED, validity, detail)`
or `stop(reason)` before closing the worker. Finalisation drains accepted work, cancels the timer and
publishes explicit terminal status; validity is supplied by the caller, never inferred from test
success. Identical terminal requests share completion; conflicting ones reject. If terminal metadata
does not fit beside accepted items, those items are published first and terminal metadata separately.

`BenchmarkCheckpointRecovery.inspect(path)` is a synchronous, read-only recovery check; run it off
event loops. It streams the file, counts history objects, checks structural/execution metadata and
reports `FINALISED` or `UNFINALISED`. Unfinalised evidence needs owner/liveness review; it is not proof
of a crash. Inspection neither resumes a workload nor changes the file, and is not a full measurement
validation or integrity check. Establish that the old owner has stopped before beginning a replacement
execution with a fresh identity. Automated restart/resume is not implemented.

A local single-thread recorder-cost probe is recorded in performance-plan §16. The repeatable
concurrent/fork calibration runner is now implemented separately (§18); the earlier probe is not
promoted to production evidence. Cadence/recovery evidence is in §17. Managed scheduler integration,
campaign orchestration and JSON-derived HTML are now connected. Growing-copy write amplification
remains a measured limitation, so version-1 preflight rejects a run whose final size, cumulative
rewrite work or two-copy peak disk estimate exceeds its declared budget.

### Controlled workload engine (B2 complete)

`BenchmarkWorkloadScheduler` accepts typed parameters, demand duration, a rate-controlled catch-up
cap, latency bounds and an injected monotonic clock. On each `advance()`, dispatch every returned
launch exactly once and report its eventual physical result with `complete(id, succeeded)`. Report
dispatch failures too. Do not block the driver on completion or JSON publication.
`BenchmarkManagedExecution` owns arrival/sampling timers, independent deadline observation, phase
transitions, bounded retry timers and stop/drain; pools and target resources remain caller-owned.

Closed-loop mode refills available physical clients after completion. Rate-controlled mode keeps an
absolute schedule independent of responses and explicitly counts excess delayed arrivals as generator
misses. Queue limits, pre-start expiry and admission rejection are enforced. Timeout is a logical
outcome: physical capacity is retained until the callback arrives, and late success/failure is counted
separately without another logical latency sample. A timeout does not cancel a PostgreSQL query.

Collect actual-boundary `checkpoint()` samples with `statistics().metrics()` for JSON measurements.
The statistics include cumulative generator misses, admission rejections, late/duplicate completions,
maximum arrival-detection/deadline-detection lag, and current physical/queue populations. Preserve
these even when logical outstanding work is zero. Record demand duration, driver cadence and the
catch-up cap in the run manifest. See performance-plan §19 for precise accounting and limitations.

The scheduler and managed-execution integration tests use real PostgreSQL and verify phase changes,
stop/drain, interval rollover and checkpoint-pressure handling. They do not establish deployment
capacity.

B2 is complete. The scheduler is connected to managed Vert.x execution, phase-specific workload
plans, stable logical-operation/physical-attempt identity, bounded retries, generator calibration,
finite campaign execution, live checkpoints and explicit stop/drain handling. The definitive full
reactor gate recorded 881 tests with zero failures, errors or skips; the benchmark module contributed
155 tests, including eleven real-PostgreSQL scheduler/scenario/campaign tests. See performance-plan
§§19 and 24–37 for the chronological implementation evidence and final scope boundary. Historical
checkpoint counts in those sections are not the current completion status.

## Parameterised characterisation campaign

Run the complete disposable local campaign from the repository root:

```shell
mvn -pl peegee-cache-benchmarks -am integration-test -Pbenchmark-characterisation -DskipTests
```

Defaults are three repetitions at each of 50 and 100 offered cache requests/second, with one-second
baseline/load/recovery phases, a two-second drain, 100 ms intervals, concurrency/pool size four,
100 deterministic entries and 256-byte payloads. Override the following Maven properties; quote
comma-separated/dotted `-D` arguments in PowerShell:

| Property prefix `peegeeq.campaign.` | Default | Meaning |
|---|---:|---|
| `outputDirectory` | `benchmark-results/characterisation-local` | Repository-relative or absolute ignored evidence directory |
| `repetitions` | `3` | Repetitions per rate, bounded to 1–100 |
| `rates` | `50,100` | Finite positive offered-rate list |
| `concurrency` | `4` | Maximum physical concurrency |
| `poolSize` | `4` | PostgreSQL pool size; pressure experiments may intentionally set this below concurrency |
| `phaseMillis` | `1000` | Each baseline/load/recovery duration; drain is twice this value |
| `sampleMillis` | `100` | Interval and checkpoint trigger; must not exceed a phase |
| `datasetCardinality` | `100` | Prepared deterministic hit dataset |
| `payloadBytes` | `256` | Deterministic binary payload size |

The profile resolves and preflights the complete experiment before PostgreSQL starts measured work.
It writes `resolved-experiment.json`, an atomically updated `campaign-manifest.json`, one UUID JSON
and one derived UUID HTML per actual run, then `characterisation-review.json`. JSON is authoritative;
HTML is self-contained and records the final JSON SHA-256. Run resources are unique namespaces and
cleanup SQL is scoped to those namespaces. The supplied pool and database are never stopped by the
adapter; the local entry point alone owns and stops its disposable Testcontainers fixture.

The review reports matrix completeness, per-run curves, onset brackets, repeat min/max p95,
limitations and follow-ups. `NO_ONSET_OBSERVED` is limited to the declared rates and durations.
`INCONCLUSIVE` is a valid review outcome when samples or required observations are insufficient.
These local results are framework/regression evidence, not production capacity.

The accepted implementation campaign is retained under
`benchmark-results/characterisation-framework-final-20260913/`: six of six VALID runs were accounted
for at 100 and 200 requests/second with three repetitions, and the review reports
`COMPLETE_NO_ONSET_OBSERVED`. See performance-plan §§31–36 for scope and verification.

External targets are represented explicitly by host, port, database, isolated schema prefix and TLS
policy, without credentials. `BenchmarkPostgresTargetVerifier` verifies the actual database,
server version and session TLS through a caller-supplied pool. External credentials and pool/server
lifecycle remain caller-owned. The repository does not currently provide a generic
`benchmark-external` Maven profile or credential-loading external main class: an owner-declared
campaign must supply the pool and call the campaign framework, or add a reviewed target-specific
launcher. Do not run an external campaign until its target, access, resource ownership, budget, stop
conditions and disruption authority are explicitly approved.

## Repeatable captured runs

### Recorder/checkpoint calibration (opt-in)

This separate command measures recorder contention, collection allocation and checkpoint growth,
not database throughput or cache latency. Run ordinary verification first, then keep other test or
build workloads out of the measurement period:

```shell
mvn -pl peegee-cache-benchmarks -am verify
mvn -pl peegee-cache-benchmarks -am integration-test -Pbenchmark-calibration -DskipTests
```

The second command launches one fresh JVM and writes one versioned JSON file under the benchmark
module's `benchmark-results/calibration/` directory (or the supplied absolute output directory).
It does not change the legacy benchmark/capture profiles. Override these calibration properties:

| Property prefix `peegeeq.calibration.` | Default | Meaning |
|---|---:|---|
| `heap` | `64m` | Child JVM maximum Java heap (`-Xmx`), not total process memory |
| `outputDirectory` | `benchmark-results/calibration` | Evidence directory; each execution gets a new UUID filename |
| `concurrency` | `4` | Simultaneous tasks sharing one recorder; safety range 1–64 |
| `bucketCount` | `128` | Finite buckets, 1–65,536; upper bounds spaced 1,000 ns apart |
| `warmupMillis` | `1000` | Recorded-loop warm-up duration; paired baseline adds the same target time |
| `measurementMillis` | `5000` | Recorded-loop measurement duration; paired baseline adds the same target time |
| `windowMillis` | `250` | Each loop's target observation window; final partial windows are retained |
| `checkpointWindows` | `4` | Publish every N pairs; safety range 1–128; final partial batch is published |
| `maximumEvidenceBytes` | `67108864` | Conservative per-file size budget, minimum 131,072; not a disk-space reservation |
| `forkIndex` | `0` | Caller-assigned fork index; UUID/process ID distinguish actual executions |

For repeated heap-constrained forks in PowerShell, use an absolute output path and invoke the command
once per fork. Example (three forks, 32 MiB heap, 4 tasks, 1,024 buckets):

```powershell
$calibrationOutput = Join-Path (Get-Location) 'benchmark-results/calibration'
foreach ($calibrationFork in 0..2) {
    mvn -pl peegee-cache-benchmarks -am integration-test -Pbenchmark-calibration -DskipTests `
        '-Dpeegeeq.calibration.heap=32m' '-Dpeegeeq.calibration.concurrency=4' `
        '-Dpeegeeq.calibration.bucketCount=1024' '-Dpeegeeq.calibration.warmupMillis=1000' `
        '-Dpeegeeq.calibration.measurementMillis=30000' '-Dpeegeeq.calibration.windowMillis=50' `
        '-Dpeegeeq.calibration.checkpointWindows=10' `
        "-Dpeegeeq.calibration.outputDirectory=$calibrationOutput" `
        "-Dpeegeeq.calibration.forkIndex=$calibrationFork"
    if ($LASTEXITCODE -ne 0) { throw "Calibration fork $calibrationFork failed; review retained evidence" }
}
```

The Java entry point also accepts nine positional arguments in the same order as its usage output:
output directory, concurrency, buckets, warm-up ms, measurement ms, window ms, checkpoint pairs,
evidence-byte budget and fork index. Direct Java execution requires the benchmark runtime classpath.
Exit codes: 0 after successful collection/cleanup, 1 for run/persistence/cleanup failure, 2 for invalid
CLI configuration. Maven reports either nonzero child exit as a failed build.

JSON preserves all warm-up and measured windows, alternating baseline/recorded order, per-task
operations/time/allocation readings, heap usage, cumulative GC readings and distributions. SUCCESS,
FAILURE and TIMEOUT rotate with **synthetic** 1,000/2,000 ns samples to exercise recording paths; these
are not measured operation latencies. The baseline performs four elapsed-clock reads, not equivalent
unrecorded database work. Compare per-task elapsed time/operations and variation across forks; do not
derive a universal overhead percentage from equal-duration loop totals or average latency percentiles.
Concurrency includes recorder monitor contention and host scheduling effects.

Separate `checkpoint-publication` measurements record each running publication's actual write duration
and resulting file size. Its interval includes the gap since the previous publication; use `writeNanos`
for write cost. That cost is written in the next publication, including the final one. Initial and
terminal self-write costs are explicitly excluded. There is no forced GC, allocation tracking is
never enabled by the runner, and unavailable counters have reasons. Heap readings are not retained
heap after GC or total process RSS. The JSON remains UNASSESSED: collection success is not acceptance.

Budget checks conservatively allow for a 32 KiB terminal reserve. A budget failure retains previous
history, attempts FAILED finalisation and reports unpublished measurement counts/cumulative outcomes;
it does not pretend unwritten distributions survived. Actual filesystem failure can still leave the
last RUNNING checkpoint. Peak disk usage includes the old and replacement files. Calibration does
not resolve the writer's growing per-checkpoint copy/hash cost or provide automatic workload resume.

### Legacy benchmark capture

The evidence-capture implementation is Java and uses the same Maven command on Windows and Linux. From the repository root, run:

```shell
mvn -pl peegee-cache-benchmarks -am integration-test -Pbenchmark-capture -DskipTests
```

The default command performs three sequential benchmark repetitions in one capture JVM. Each repetition uses a controlled 5-second warm-up before every measured 30-second workload, 8 foreground workers, a 12-connection pool, PostgreSQL 18.3, and the standard acceptance gates. Warm-up operations are discarded before the latency histograms and throughput clocks start. It skips the ordinary test suite so test execution does not become an uncontrolled warm-up or thermal load. The Java runner receives typed results directly from `CacheBenchmarkMain`; it never parses Maven or console output to reconstruct measurements.

Repetitions recreate benchmark resources but are not statistically independent process forks: JVM compilation state, host temperature, and operating-system caches can carry across runs. The HTML report is therefore repeatable regression evidence, not a substitute for a forked benchmark methodology when independent samples are required.

For release evidence from a clean checkout:

```shell
mvn verify
mvn -pl peegee-cache-benchmarks -am integration-test -Pbenchmark-capture -DskipTests \
  '-Dpeegeeq.benchmark.capture.runs=3' \
  '-Dpeegeeq.benchmark.warmupSeconds=5' \
  '-Dpeegeeq.benchmark.durationSeconds=30' \
  '-Dpeegeeq.benchmark.capture.requireCleanGit=true' \
  '-Dpeegeeq.benchmark.capture.topology=Dedicated benchmark host; local Docker PostgreSQL; no competing workloads; performance power profile'
```

The backslashes above are shell line continuations; PowerShell users can put the command on one line or replace them with PowerShell backticks. The Maven properties and Java behavior are identical on both platforms.

For a short end-to-end capture smoke, run:

```shell
mvn -pl peegee-cache-benchmarks -am integration-test -Pbenchmark-capture -DskipTests '-Dpeegeeq.benchmark.capture.runs=1' '-Dpeegeeq.benchmark.warmupSeconds=1' '-Dpeegeeq.benchmark.durationSeconds=2'
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

For meaningful comparisons, keep the Git commit or recorded dirty state, benchmark configuration, PostgreSQL image digest, topology, Docker CPU/memory allocation, power policy, and competing host workload equivalent. Use a clean worktree for release evidence, avoid unrelated workloads, and allow the host to reach a stable thermal state. Treat the per-scenario warm-up as mitigation rather than proof that JIT, run-order, cache-state, or thermal bias has been eliminated. The current harness provisions PostgreSQL through local Testcontainers; the topology description must not imply that it benchmarks a remote database. Local Docker results remain regression evidence, not production capacity commitments.

## Direct Maven execution

The benchmark can still be run without an HTML evidence report. Run the default 30-second scenarios:

```shell
mvn -pl peegee-cache-benchmarks -am integration-test -Pbenchmark -DskipTests
```

For a short smoke run:

```shell
mvn -pl peegee-cache-benchmarks -am integration-test -Pbenchmark -DskipTests '-Dpeegeeq.benchmark.warmupSeconds=1' '-Dpeegeeq.benchmark.durationSeconds=2'
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
- `peegeeq.benchmark.warmupSeconds` (default: 5; unrecorded per-scenario warm-up)
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
