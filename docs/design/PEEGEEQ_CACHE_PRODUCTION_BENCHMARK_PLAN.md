# Parameterised performance and degradation analysis plan

**Revised:** 15 September 2026
**Status:** B0–B5 COMPLETE; B6 COMPLETE FOR THE DECLARED LOCAL FRAMEWORK-ACCEPTANCE CAMPAIGN — external/production campaigns remain owner-authorised future work and no production capacity claim is made
**Current implementation verified:** `f983355` (`feat(benchmarks): complete characterisation framework and campaign reporting`)
**Original source baseline reviewed:** `2de7be1` (`Refactor code structure for improved readability and maintainability`)
**Scope:** parameterised benchmarking across workloads, runtime configurations, timeframes and deployments

## 1. Purpose and correction to the original plan

Build a repeatable experimental framework that exposes when, how, and under which combinations of
parameters PeeGeeQ Cache starts to degrade. Collect statistics throughout configurable experiments,
not just a summary at the end. Extract performance trends, identify degradation onset and progression,
and measure recovery after pressure is reduced.

This replaces the earlier single-deployment, predefined-SLO acceptance approach. A customer workload,
production endpoint, expected peak throughput, or latency target is not a prerequisite for implementing
or exercising the framework. Deployments are experimental configurations, not categories whose
universal limits can be inferred from one representative run. Claims apply to tested settings and
ranges; untested combinations remain unmeasured.

The primary output is a degradation profile: parameter settings, workload and load history, elapsed
time, observed symptoms, supporting measurements, uncertainty, and subsequent recovery behavior.
Optional SLO overlays can be applied later. They do not define the experiment or replace degradation
analysis. Safety limits protect infrastructure; validity checks protect the evidence. Neither is a
performance target.

Infrastructure rental, external access, production mutations, fault injection and publication require
their own authority. Local disposable Testcontainers experiments and framework development can proceed
without those external decisions. Maven publication and unrelated product features are outside scope.

## 2. Governing rules and source-level gap assessment

Follow the [main implementation plan](archive/PEEGEEQ_CACHE_IMPLEMENTATION_PLAN.md),
[benchmark runbook](../PEEGEEQ_CACHE_BENCHMARKS.md),
[TDD standard](../guidelines/PEEGEEQ_CACHE_TEST_COVERAGE_AND_TDD_APPROACH.md), and applicable coding,
configuration, isolation and Vert.x rules in the
[development guidelines](../guidelines/PEEGEEQ_CACHE_DEV_GUIDELINES.md).

Implement test-first in small slices: inspect RED, implement, inspect GREEN, then refactor. No Mockito
or substitute mocking framework. Database tests use real PostgreSQL through Testcontainers. Pure
statistics and timing-policy tests use explicit sample streams and injected time, not fabricated
database performance. Inject configuration; do not mutate JVM system properties in tests. Preserve
Vert.x Future composition, bounded ownership, primary failures and cleanup diagnostics.

The reviewed implementation requires more than external-database connectivity:

| Current implementation | Limitation | Required change |
|---|---|---|
| `BenchmarkScenarioResult` stores one operations/throughput/p50/p95/p99 summary | Cannot locate onset, spikes, drift or recovery within a scenario | Timestamped interval measurements and parameter changes |
| `runWorker` waits for completion before issuing another operation | Offered load falls when responses slow | Explicit closed-loop and rate-controlled modes with scheduling/queue accounting |
| First failed operation aborts a workload | Cannot observe progression under overload | Record outcomes and continue within bounded experimental safety rules |
| Operation timeout derives from the p99 gate | Measurement deadline depends on a desired outcome | Deadlines independent of analysis thresholds and optional SLOs |
| `LatencyHistogram` grows an array and sorts snapshots | Memory/snapshot cost grows with sample count | Bounded interval distributions, streaming evidence and tested merging |
| Fixed key/payload patterns, scenario order and isolated expiry measurement | Narrow coverage and possible order/state confounding | Parameterised scenarios, repeatable datasets and explicit reset/order policies |
| Three repetitions in one capture JVM | Shared process state affects comparisons | Recorded forks, repetitions, seeds and reset policy |
| Local `PgTestSupport`, fixed schema/application name | No external target; fixed ownership cannot be generalised safely | Explicit target adapters and isolated run resources |
| Session termination followed by a successful write | Pool reconnection is not HA failover or sustained service recovery | Distinct events, observations and scope of claims |

Keep existing regression profiles and gates operational. Add characterisation explicitly; do not
silently change what a legacy regression pass means.

## 3. Experimental model

### 3.1 Versioned specification

Define an immutable, versioned experiment specification and validate it before workload launch.
It contains scenarios, fixed/variable factors, units/ranges, supported combinations, timeframe
schedule, measurement policy, safety limits, repetitions/forks, seed, reset rules, instrumentation
and evidence destination. Resolve it to a complete run manifest. Reject unknown parameters,
non-finite numbers, invalid units and incompatible combinations. Preserve requested and resolved
values; never silently substitute defaults for unsupported cases.

Separate three parameter classes:

- **Fixed within a run:** deployment, database/JVM version, compute/storage, schema/bootstrap and
  settings requiring reconstruction. Change between runs and record reset effects.
- **Scheduled within a run:** offered rate, supported concurrency controls, workload mix or another
  explicitly live factor. Record intended/actual transition times; mark mixed transition windows.
- **Observed, not controlled:** interference, actual network latency, GC, pool waits, database contention,
  storage response and thermal behavior. Record instrumentation availability and sampling limits.

| Family | Parameterised dimensions |
|---|---|
| Load | Arrival model/rate, concurrency, client count, bursts, bounded queue/in-flight limits |
| Workload | Operation mix, counter/lock contention, publishers/subscribers, batch size, telemetry mode |
| Data | Cardinality, working-set bytes, payload distribution, namespaces, hit/miss ratio, hot-key skew |
| Lifecycle | TTL distribution, sweeper interval/batch size, growth/churn, optional write-behind |
| Runtime | Pool capacity, independent deadlines, heap/JVM options, processes and resource limits |
| Deployment | Actual client/database placement, version, compute/storage, network/TLS, proxy and HA |
| Time | Warm-up, baseline, step/ramp, dwell, sustained load, recovery, sampling and analysis windows |

These dimensions are a support backlog, not a claim of existing coverage. Publish a capability
inventory as each is implemented. Do not impose the legacy pool-greater-than-worker constraint on
every characterisation experiment: controlled pool pressure is a variable to investigate. Retain
legacy validation in the regression path; separately bound resources in the characterisation path.

### 3.2 Matrix and experiment design

Expand explicit factor values/ranges into a finite, inspectable matrix with estimated run count,
duration and resource requirements. Do not blindly execute an unbounded Cartesian product.

1. Establish low-pressure reference behavior for each scenario/configuration.
2. Sweep selected factors while holding others fixed; repeat to assess variation.
3. Investigate interactions using selected factorial combinations, such as rate × pool size or
   payload × working-set size. Record exclusions; single-factor evidence is not interaction evidence.
4. Refine parameter intervals around candidate boundaries in separately identified follow-up runs.
   Retain the original sweep and the refinement rule.
5. Confirm boundaries with repeated sustained runs, down-sweeps and recovery observations.

Use seeded randomisation or counterbalanced order for independent configurations where appropriate;
preserve intentional ramp order. Record dataset seed, warm/cold/reset conditions, JVM fork and
prior load history. A new JVM does not reset database buffers, OS caches, storage or temperature.
Retain at least three independent capture invocations per selected release-evidence configuration,
but do not treat three as statistical proof. Repetition counts and uncertainty review are configurable;
noisy boundaries require additional observations or an inconclusive result.

## 4. Timeframes and load execution

Model timestamped phases: preparation, warm-up, reference baseline, load steps/ramp, sustained
observation, reduced-load recovery, then drain/cleanup. Phases are selectable, not universally
mandatory. Duration, step size, ramp rate, sampling interval, analysis window/stride and recovery
period are independent parameters.

Keep warm-up records for context but exclude them from steady-state comparisons. Preserve transitions
and unstable periods. If a configured period never stabilises, report that rather than discarding
data to create apparent stability. Constant-load soaks must expose elapsed-time trends and recurring
events such as backlog growth, GC and checkpoints.

Support distinct workload models:

- **Closed loop:** bounded clients issue work after completion, with optional think time. Report
  achieved demand and concurrency, not an independently maintained arrival rate.
- **Rate controlled:** schedule arrivals independently of completion with bounded admission, queue
  and in-flight limits. Count scheduled, admitted, started, completed, rejected, expired-before-start
  and outstanding work. Record schedule lag and missed launches when the generator cannot keep up.
  Do not silently lower reported demand or introduce an uncontrolled catch-up burst.

Record scheduled-to-completion latency separately from started-to-completion latency, with queue
and scheduling delay. Delayed/rejected work must not disappear because it never reached PostgreSQL.
Record client-observed timeouts separately from late database completions and reconcile without
double-counting logical outcomes. Make retries explicit, counting both logical requests and physical
attempts. A timeout is not a known successful latency sample.

## 5. Measurement and evidence contract

### 5.1 Interval records and accounting

Use monotonic time for durations and interval boundaries, plus UTC anchors for external correlation.
For multiple hosts record clock alignment/uncertainty; their monotonic clocks are not interchangeable.
Use half-open intervals and test boundary events, late completion, empty/partial intervals and drain.
Compute rates using actual interval duration, not an assumed timer cadence.

Every record carries experiment/configuration/run/fork/scenario/phase identity, start/end offset,
active parameters and transitions. Retain:

- Scheduled, admitted, started, successful, failed, timed-out, rejected and outstanding counts, with
  documented accounting identities. Separate completion-window rates from arrival-cohort analysis.
- Offered/start/completion/success rates; queue/in-flight depth and growth.
- Per-operation distributions, counts and p50/p95/p99 with precision/range metadata. Keep successful
  service latency, unsuccessful attempt duration and scheduled-to-completion latency distinct.
- Cache hits/misses, lock attempts/acquisitions/contention, verified counter changes, publish/receive
  counts, logical expiry and physical-removal backlog/lag as appropriate to the scenario.
- Generator CPU/GC/memory, scheduling lag, event-loop delay and collector cost; pool occupancy/wait/
  timeouts; database CPU, sessions, waits/locks, storage, WAL/checkpoints and network observations
  where available. Missing or unsupported metrics are not zero.

Define operation units: SET+GET workflow, database request, lock attempt and delivered notification
are not interchangeable. Never combine them into an unlabeled operations/s figure. Flag low-count
tail estimates using a configurable sample sufficiency rule; empty intervals have no percentile,
not zero latency. Keep counts visible with percentile results.

### 5.2 Bounded recording and retention

Use bounded-memory interval distributions with documented precision/range, merge semantics and
overflow handling. Do not copy/sort all historical samples on event loops. Stream interval records
and retain mergeable distributions for later analysis. Ensure consistent counter/distribution
snapshots and test concurrent recording during interval rollover.

The user requires **one comprehensive, versioned JSON file per actual run** as the authoritative
result. Embed the resolved configuration, run manifest, environment, full interval measurements,
diagnostics, distributions, outcomes and analysis in that file; do not require a separate result
stream or database to reconstruct a run. HTML is a derived human-readable view of the JSON.
Retain valid partial JSON checkpoints throughout execution using atomic replacement. Temporary
staging files are implementation details, not additional authoritative result files.
Bound writer queues; lost records and writer failures are measurement-validity failures, not silent
data loss. Do not reconstruct authoritative measurements by parsing console logs.

Separate execution status (completed/stopped/failed), measurement validity and observed degradation.
A valid completed experiment may demonstrate severe overload. A good throughput number cannot make
missing evidence valid. Preserve independent regression pass/fail behavior.

## 6. Trend and degradation analysis

Version the analysis policy: reference selection, metrics, window/stride, minimum samples, effect-size
rule, persistence rule, slope/change criterion and recovery rule. These are detector settings, not
production SLOs. Retrospective exploration is allowed but preserves the original policy and identifies
subsequent versions; do not present exploratory findings as predetermined confirmation.

Implement interpretable window-based detectors first:

| Symptom | Evidence |
|---|---|
| Latency deterioration | Persistent distribution/tail increase against matched reference; counts and absolute/relative change |
| Capacity knee/saturation | Diminishing useful-throughput gain as offered load rises, considered with latency and outstanding work |
| Accumulating pressure | Sustained queue, in-flight, pool-wait or expiry-backlog growth |
| Reliability deterioration | Failures/timeouts/rejections and their timing, not only successful-response latency |
| Time-dependent degradation | Trends under constant load aligned with runtime/database events |
| Recovery/hysteresis | Return toward reference after reduced load, residual backlog, ascending/descending differences |
| Measurement limitation | Generator saturation, missing observations or insufficient samples prevent system-level conclusions |

Emit candidate onset, confirmation time, reference, affected metric(s), parameter/time interval,
persistence, magnitude and evidence links. Bracket onset at the resolution of measured intervals/
load steps rather than claiming an exact limit. Keep isolated spikes visible without automatically
classifying them as persistent change. “No onset observed” applies only to the tested range/timeframe.
Use INCONCLUSIVE when measurement quality, reference or samples cannot support a conclusion.

Do not average percentiles into a pooled percentile. Merge compatible distributions for pooled
queries and show per-run variation separately. Adjacent/overlapping windows are not independent
repetitions. State the uncertainty method and its assumptions; do not manufacture confidence from
the number of correlated intervals.

Align symptoms with resource events and parameter transitions. Correlation generates hypotheses;
controlled follow-up experiments test them. For example, change pool size while preserving arrival
schedule and dataset, and check both boundary movement and pool-wait behavior. Retain contradictory
results and distinguish measured symptoms from proposed causes.

## 7. Scenario programme

Use existing real cache, counter, lock, Pub/Sub, telemetry and expiry operations as the foundation.

1. **Load sensitivity:** steps/ramps with dwell and down-sweep; record earliest reproducible symptoms
   and progression, not just the fastest achieved rate.
2. **Contention/data sensitivity:** hot-key, payload and working-set sweeps plus selected interactions;
   verify correctness alongside performance.
3. **Duration sensitivity:** constant load, TTL/data churn and configurable soaks; expose drift and
   periodic database/runtime effects.
4. **Burst/recovery:** bounded bursts, buildup, drain and reduced-load recovery without restarting
   away the state being investigated.
5. **Instrumentation effects:** matched telemetry modes and sampling cadence; quantify observer cost
   and identify generator/collector limits.
6. **Fault extensions:** verified run-owned connection loss and subscription restoration; authorised
   restart/HA cases where available. Keep these separate from degradation under ordinary load.

## 8. Ordered implementation and exit evidence

This sequence supersedes the earlier B0–B6 plan. Production target selection is no longer an entry gate.

| Phase | Implementation and exit evidence | Status |
|---|---|---|
| B0 — Experiment/interval contracts | Immutable factors/timeframes, finite matrix expansion, interval/outcome schema; RED/GREEN validation, boundaries and accounting | COMPLETE — complete specification resolves phases, configurations, scenario/runtime controls, persistence estimates, capabilities, run count and evidence destination before launch |
| B1 — Time-series recorder | Bounded distributions, rollover, partial streaming evidence; precision/concurrency/overflow tests and recorder-cost measurements | COMPLETE — bounded recorder/checkpoints and calibration retained; the selected finite single-JSON strategy now preflights final bytes, cumulative rewrite work and two-copy peak disk, rejecting unsupported soaks before launch |
| B2 — Scheduled workloads | Closed-loop/rate-controlled execution, independent deadlines, bounded queues, phase schedule and complete outcomes; real PostgreSQL and generator-pressure tests | COMPLETE — scheduling/accounting, managed Vert.x execution, piecewise phase rates/concurrency, deterministic workload mixes, stable retry/attempt identity, bounded generator sweeps, finite resolved-run execution, stop/drain policy and live checkpoints implemented and verified |
| B3 — Scenario/diagnostic parameters | Dataset/mix/contention/TTL/runtime controls, capability inventory; correctness, repeatability and diagnostic-availability tests | COMPLETE — deterministic cache/counter/lock/scan adapters, cursor completeness checks, runtime safety evaluation and versioned availability inventory are verified; unsupported capabilities remain explicit |
| B4 — Analysis/reporting | Versioned policies, onset/progression/recovery output and linked time-series report; known-trace tests and controlled repeated live experiments | COMPLETE — version-1 persistent latency/reliability/pressure analysis, isolated-spike handling, recovery, inconclusive results and self-contained hash-linked HTML are implemented and exercised by a repeated local campaign |
| B5 — Deployment/campaign execution | Explicit targets, isolated ownership, matrix/repetition/reset orchestration and manifests; local/external-path integration tests before external campaigns | COMPLETE — explicit target verification, deterministic order, run-owned reset/cleanup, failure preservation, live manifest, per-run analysis and local/external-supplied PostgreSQL path tests are implemented |
| B6 — Characterisation review | Retained curves, boundary brackets, repeat-run variation, limitations, follow-ups and completeness review for the executed matrix | COMPLETE FOR DECLARED LOCAL SCOPE — six of six finite local runs reviewed with retained curves/variation and tested-range limitations; no external or release-readiness claim |

B0–B4 can be implemented and verified using local disposable PostgreSQL. External availability does
not block them. Bring resource isolation forward wherever concurrent local experiments require it.
A model class or one smoke run does not complete an entire phase.

### Verification ladder

- Pure logic: factor expansion, interval boundaries, counter conservation, distribution merging,
  empty/low-count data, stable/spike/drift/step/recovery/missing-data detector traces.
- Concurrency/lifecycle: rollover races, late outcomes, timeouts, writer pressure, cancellation,
  partial startup, cleanup and preserved primary failures.
- Real PostgreSQL: workload semantics, bounded overload/recovery, isolation and no cross-run cleanup,
  actual traffic and telemetry through Testcontainers.
- Calibration: schedule fidelity, memory bounds, recorder cost and signal detection in controlled
  experiments. Synthetic unit-test traces remain labelled as such and are never published as
  measured PeeGeeQ performance.
- Regression: inspect actual focused RED/GREEN test execution, then full reactor and applicable
  PostgreSQL 15–18 matrix for changed database behavior. Review logs, skips/errors, banned patterns
  and evidence completeness rather than exit code alone.

Retain incomplete and failed attempts. Expected overload outcomes belong in the data; correctness
violations, mis-targeting, uncontrolled resource growth or broken measurement require a recorded stop.
Do not silently suppress failures or abort characterisation at its first ordinary overload symptom.

## 9. External targets and fault safety

Keep Testcontainers as the current local default. Add explicit external configuration without
fallback, actual server identity/version and TLS validation. Use configurable schema and unique
run-owned sessions. Never stop an externally owned database; bootstrap and cleanup touch only
validated benchmark resources. Test an externally supplied Testcontainers endpoint surviving
completion along with unrelated schemas/sessions and concurrent runs.

Credentials remain externally supplied, not committed or embedded in evidence. Resolve endpoint,
access, budget, stop conditions and permissions before each external campaign, not before framework
development. A topology label does not transform a local run into remote evidence.

External disruption is off by default. Session termination must verify database, role and unique
run identity; absent permission or absent disruption is NOT RUN/FAILED, not instant recovery.
Restart/HA tests require the approved infrastructure procedure and independent recovery access.
Distinguish fault start, first success and sustained recovery. Pub/Sub reconnection does not prove
delivery of non-durable messages missed while disconnected.

Screenshots are supplementary, not measurements. If used, capture actual browser state without
masks, replacement values or capture-time state changes. Leave existing screenshot artifacts/policy
unchanged during benchmark development.

## 10. Deliverables and completion

Deliver a comprehensive JSON result per execution containing reproducible configuration,
source/build/runtime identities, requested/actual parameter transitions, interval statistics and
distributions, correlated diagnostics/events and analysis. Derive the self-contained HTML report
from that JSON. Include time-series and load-response curves, selected interaction comparisons,
and onset/recovery annotations linked to observations. Show variation, suspected mechanisms,
inconclusive cases, excluded combinations and tested range/timeframe limits.

Retain seeds, reset history, analysis version, hashes and cleanup results. Generated measurements
remain ignored, using the existing `benchmark-results/` local convention and an owner-selected
archive. Do not publish or commit bulk data without instruction. Update the runbook, main plan
and handover when capabilities and verification change; distinguish planned options from runnable ones.

Framework completion means B0–B5 capabilities and verification are delivered, not that all deployments
have been measured. Campaign completion means its declared finite matrix, repetitions and analysis
are accounted for, including observed degradation and inconclusive outcomes. Keep release-readiness
open until B6 evidence for the declared scope is reviewed. Degradation is a finding, not automatically
a failed benchmark or a reason to weaken thresholds.

## 11. Immediate implementation step

The implementation sequence is complete for the declared local framework scope; see §§31–37.
The next work is not an unimplemented framework phase: it is an owner-declared external campaign or
a separately versioned detector/scenario extension. Before either, choose the target, authority,
finite matrix, persistence budgets and required diagnostics. Automatic in-place restart/resume is
deliberately unsupported; begin a new execution identity after independent owner/liveness review.
There is no generic `benchmark-external` Maven profile or credential-loading external entry point in
the current codebase. The first external campaign must supply a caller-owned pool to the implemented
target verifier/campaign runner, or introduce a reviewed campaign-specific launcher.

## 12. Implementation evidence — first B0 slice (6 September 2026)

> **Historical implementation diary:** Sections 12–30 preserve the status observed after each strict-
> TDD slice. Statements such as `IN PROGRESS`, `NOT STARTED`, "remaining work" and "does not yet"
> in those sections are checkpoint history, not current status. The phase table in §8, the immediate
> step in §11 and the completion evidence in §§31–37 are authoritative for the current codebase.

Implemented in the benchmark module:

- `BenchmarkTimeline`: immutable named phase schedule with explicit phase kinds, positive durations,
  a configurable sampling interval, and lazy half-open window lookup. Sampling resets at each phase
  boundary so a planned window does not mix warm-up, load and recovery. Short terminal phase windows
  are marked partial. No per-window allocation at construction; long schedules remain bounded by
  their phase count. Overflow and duplicate names fail validation.
- `BenchmarkWorkCounts`: validated cumulative logical-request counters. Scheduled work can remain
  pending admission; admitted work can remain queued or expire before starting; started requests
  finish as success, failure or timeout. These terminal outcomes are mutually exclusive. Physical
  retries and late physical completions need their own later contract, not outcome reclassification.
- `BenchmarkInterval`: deltas between coherent snapshots over actual observed elapsed time. A window
  may complete more requests than it starts because prior work can finish in it. Counter resets and
  outcome reclassification are rejected. Rates do not use the planned sampling cadence as a substitute
  for actual duration. This class does not invent latency distributions for empty windows.

Accounting identities (all counters cumulative within one run/scenario):

- `scheduled = admitted + rejected + pendingAdmission`
- `admitted = started + expiredBeforeStart + queued`
- `started = succeeded + failed + timedOut + inFlight`
- `outstanding = pendingAdmission + queued + inFlight`

The caller must supply coherent same-run/scenario snapshots. The interval model is not itself a
thread-safe event recorder or a request-identity tracker. Planned windows and observed intervals
are separate so delayed timer callbacks cannot silently rewrite measurement duration.

Strict TDD: `target/benchmark-b0-red.log` records the expected missing-contract compilation failure;
`target/benchmark-b0-green.log` records 11 executed tests, zero failures/errors/skips, across
`BenchmarkTimelineTest` (5) and `BenchmarkIntervalTest` (6). The focused selection permits upstream
modules with no matching named tests; the benchmark module's actual 11-test execution was verified.
Broader verification passed with `mvn --batch-mode --no-transfer-progress -pl peegee-cache-benchmarks -am verify`
in `target/benchmark-b0-verify.log`: all eight selected reactor modules succeeded in 1:31, finishing
at 12:38:52 +08:00 on 6 September. The 59 Surefire XML reports contain 409 tests, zero failures,
errors or skips; 27 tests belong to the benchmark module. Existing real PostgreSQL tests ran on
18.3-alpine. Log review found the known Maven/Guice deprecation, idempotent-schema notices and
intentional connection-loss test diagnostics, with no Maven test errors. New source passed
whitespace and banned-pattern review. This is benchmark/dependency verification, not a fresh
11-module UI/browser acceptance or a new four-version database matrix; no database behavior changed.

Remaining B0 work includes experiment/run identity, versioned typed parameters, finite matrix
expansion, and the complete measurement schema. B1–B6 remain NOT STARTED. Existing benchmark
profiles, database execution and HTML report behavior are unchanged; no new execution flags or
performance results are claimed by this foundational slice.

## 13. Implementation evidence — typed load matrix and experiment slice (6 September 2026)

This advances the remaining-work snapshot in section 12; B0 is still IN PROGRESS.

- `BenchmarkParameters` defines closed-loop versus rate-controlled load, concurrency, pool size,
  offered rate in requests/second, bounded queue capacity, and an independent operation deadline.
  Pool size may be below concurrency for pressure experiments. Rate-controlled demand must be
  finite and positive; closed-loop demand has no independent offered rate and uses zero. Resource
  limits and nanosecond-representable deadlines are validated without consulting global state.
- `BenchmarkParameterMatrix` freezes explicit concurrency/pool/rate axes and rejects empty,
  duplicate or invalid values before resolution. Closed-loop positive and negative zero cannot
  inflate the configuration count. Declaration order is retained: rate varies fastest, followed
  by pool, then concurrency. Configuration lookup is lazy; a test resolves the last of 200 million
  combinations from two 10,000-value axes without materialising the product.
- Integer range expansion supports ascending and descending steps with an explicit maximum value
  count checked before allocation. The inclusive bound is not a forced endpoint: 1 through 6 by 2
  yields 1, 3, 5. Arithmetic uses a wider representation to avoid integer overflow.
- `BenchmarkExperiment` validates schema version 1, experiment identity, repetition/fork counts,
  aggregate run budget and overflow before exposing run descriptors. It deterministically maps a
  run ordinal to experiment/configuration/fork/repetition identity, parameters, timeline and workload
  seed. Planned duration is the sum of phase durations across runs, excluding process startup,
  provisioning and uncontrolled reset overhead. Fork indices are planned descriptors, not evidence
  that independent JVMs have been launched. The shared workload seed is preserved for matched data,
  not silently transformed into a randomisation policy.

Strict TDD evidence:

- `target/benchmark-b0-matrix-red.log`: expected missing-class compilation RED before implementation.
- `target/benchmark-b0-matrix-green.log`: initial 12 new tests passed.
- `target/benchmark-b0-matrix-zero-red.log`: additional signed-zero regression failed its assertion
  before the fix (7 matrix tests executed, 1 failure).
- `target/benchmark-b0-matrix-final-green.log`: 24 B0 tests passed, zero failures/errors/skips;
  13 belong to this slice and 11 to the prior timeframe/accounting slice.
- `target/benchmark-b0-matrix-verify.log`: all eight selected reactor modules passed in 1:21,
  finishing at 12:45:03 +08:00. The 62 fresh Surefire XML reports contain 422 tests, zero failures,
  errors or skips; the benchmark module has 40 tests. Existing PostgreSQL 18.3 integration tests
  ran. Log review classified Maven/Guice deprecation, idempotent-schema notices, deliberate Pub/Sub
  disconnects and the existing write-behind persistent-failure test diagnostic; no unexplained
  test failure was found. Whitespace and new-source banned-pattern review passed. This is the
  benchmark/dependency reactor, not a new 11-module browser run or four-version matrix.

Capability boundary: this is a typed Java planning API with a limited independent-axis load matrix.
It does not yet parse a manifest/configuration file, fingerprint complete manifests, specify workload
scenarios/deployments, randomise run order, exclude selected combinations, schedule live parameter
transitions, launch forks or drive PostgreSQL. Matrix size and the run budget are planning bounds;
queue/concurrency fields are not enforced by a live scheduler until B2 is connected. Existing
regression commands remain unchanged. Measurement identity/schema completion and manifest
representation remain B0 work; B1–B6 are NOT STARTED.

## 14. Implementation evidence — per-execution JSON checkpoints (6 September 2026)

The user clarified that a comprehensive JSON file for each run is the primary evidence artifact.
This slice establishes the persistence boundary; it does not yet execute characterisation workloads.

- `BenchmarkRunEvidence` is an immutable snapshot of one actual execution, with a UUID distinct
  from planned experiment/configuration/fork/repetition identity. It includes plan version and seed,
  resolved parameters, named phases and sampling cadence, UTC start/checkpoint times, execution
  status and finalisation, caller-supplied measurement validity, environment metadata, scenario/unit/
  phase-labelled observed intervals, complete cumulative counters, elapsed-time rates, scalar
  metrics with units and unavailable reasons, and timestamped diagnostics.
- JSON format version 1 preserves signed 64-bit counters/offsets/seeds as JSON integer literals.
  Consumers must use an integer-capable parser; converting every JSON number to a JavaScript
  floating-point number is not an exact round trip for all valid values.
- Execution status is separate from validity. Unfinalised checkpoints remain `RUNNING` with
  `finalised: false`; failed/stopped runs retain measurements and a reason. An abrupt process exit
  leaves the last published checkpoint visibly unfinalised, rather than claiming completion.
  No automatic restart/recovery supervisor is implemented by this slice.
- Scalar missing measurements use `null` plus a reason, never a fabricated zero. Latency distributions
  are explicitly `NOT_COLLECTED` and trend analysis `NOT_RUN` until their implementations are connected.
  Environment metadata is supplied by the caller here, not newly probed from a live deployment.
- `BenchmarkRunJsonWriter.create` exclusively reserves `<execution UUID>.json` in the supplied
  output directory. Checkpoints replace that same file atomically; unsupported atomic replacement
  fails without a weaker fallback. Failed writes do not advance the writer's state; temporary-file
  cleanup preserves the primary exception. Existing execution files are not overwritten on create.
- Subsequent checkpoints preserve identity, configuration, start time, environment and prior
  measurement/diagnostic history. They reject time regression, truncated/rewritten history and a
  target already changed outside the writer. Terminal snapshots cannot be reopened. This is a
  single-owner contract, not concurrent multi-process coordination; output directories must not
  be modified by other actors while a checkpoint is being written.
- Same-scenario intervals must be contiguous, retain operation units and carry counters forward.
  Invalid metrics, unknown phases and negative identity/diagnostic offsets fail validation.

Strict TDD evidence: `target/benchmark-json-red.log` records missing-contract compilation RED;
`target/benchmark-json-green.log` records the first six passing tests. The added external-file-change
regression failed before its fix in `target/benchmark-json-ownership-red.log`.
`target/benchmark-json-final-green.log` records 31 passing B0 tests, including seven JSON tests,
with zero failures/errors/skips. Broader verification in `target/benchmark-json-verify.log` passed
all eight selected reactor modules in 1:37 at 13:59:13 +08:00: 429 tests across 63 fresh Surefire XML
reports, zero failures/errors/skips, including 47 benchmark-module tests and existing PostgreSQL
18.3 integration tests. Log review found the known tooling/schema notices and deliberate Pub/Sub
and write-behind fault-test diagnostics, not unexplained failures. Source whitespace, banned-pattern
checks and plan links passed. No new full UI/browser run or four-version matrix is claimed.

Remaining limits: each checkpoint currently serialises the full in-memory snapshot and rewrites
the JSON. This API must run off event loops; it is not a bounded-memory high-frequency recorder or
a power-loss durability guarantee. B1 must implement and measure bounded collection/checkpoint
cadence without changing the one-authoritative-JSON-file requirement. Distribution retention,
analysis, complete environment capture and JSON-derived HTML remain pending. Legacy benchmark
commands still produce their original HTML report and are not silently rerouted through this API.

## 15. Implementation evidence — bounded latency and interval recorder (6 September 2026)

This slice advances B1 while the complete B0 experiment/measurement contract remains in progress.
It connects real interval distributions to the existing per-execution JSON API, not yet to a campaign
entry point or a rate-controlled load scheduler.

### Measurement representation

`BenchmarkLatencyDistribution` retains explicit disjoint bucket upper bounds, counts and overflow:
`[0,b0], (b0,b1], ...`, followed by values above the last bound. Bounds are supplied explicitly,
strictly increasing and non-negative; a distribution permits at most 65,536 finite buckets.
Precision is therefore the declared bucket layout, not an assumed number of significant digits.
The recorder does not retain individual samples or resize buckets as latency rises.

Percentiles use nearest rank and are explicitly labelled **bucket upper bounds**, not exact latency
samples or interpolated estimates. Rank arithmetic retains signed-64-bit sample-count precision.
No samples, or a rank falling into overflow, yields a null percentile with diagnostic context;
overflow is never silently clamped to the final finite bound. Sample counts remain visible; formal
sample-sufficiency policies are still part of the forthcoming analysis layer. Compatible
distributions merge by adding counts, not averaging percentiles; incompatible layouts and count
overflow fail validation.

### Recorder and JSON integration

- `BenchmarkIntervalRecorder` is a synchronised, single-scenario accumulator with an injected elapsed
  nanosecond clock. It retains eight cumulative request counters and six fixed-size bucket arrays,
  separating service and end-to-end durations for successful, failed and timed-out requests.
- Event recording validates the available request population before mutation. Pending admission,
  queued work, rejections and expiry-before-start remain explicit. Completion/timeout request identity
  still belongs to the future scheduler; this aggregate recorder does not deduplicate callbacks.
- Clock reads and mutations share the recorder lock. Checkpoints close actual observed half-open
  intervals, snapshot counts and distributions together, then reset only interval distributions.
  Work can start in one interval and finish in a later one. No completed history is retained inside
  the recorder; immutable emitted snapshots belong to the consumer.
- A checkpoint must be strictly later than its start and last recorded event. A same-tick or regressed
  boundary is rejected without dropping data; the caller retries once time advances. An event at the
  preceding interval's end belongs to the next interval. Timer-driven retry and scheduling are not
  provided by this recorder.
- The hot event path uses preallocated primitive counter/bucket arrays and binary search; snapshots
  allocate bounded-by-layout immutable distributions. Synchronisation and snapshot work still have
  costs that require calibration; this change does not establish an overhead percentage.
- `BenchmarkRunEvidence.Measurement` now accepts the six named distributions and checks their sample
  counts against the interval's successful/failed/timed-out outcomes. JSON uses `COLLECTED` with full
  layouts, counts, overflow and percentile representation. The earlier no-distribution constructor
  remains explicitly `NOT_COLLECTED`; empty collected series have zero samples and null percentiles.
  Analysis remains `NOT_RUN` until its implementation is connected.

### Verification

- `target/benchmark-recorder-red.log`: missing distribution/recorder contracts failed compilation
  before implementation; `target/benchmark-recorder-green.log`: the first nine tests passed.
- `target/benchmark-recorder-accounting-red.log`: the added outcome/sample-count consistency test
  failed before the evidence validation fix. `target/benchmark-recorder-final-green.log`: 17 focused
  recorder/distribution/JSON tests passed, zero failures/errors/skips (10 new pure recorder tests
  plus seven existing JSON tests).
- Pure tests cover bucket boundaries, overflow, empty distributions, exact large-count ranks, merge
  compatibility, invalid input, half-open intervals, retained work, failed checkpoint preservation,
  overload accounting, and four concurrent writers recording 8,000 completions across two coordinated
  interval windows. This verifies concurrency/accounting, not a performance benchmark.
- Supplemental `BenchmarkRecorderIntegrationTest` uses the real PostgreSQL cache service and standard
  Testcontainers lifecycle. Forty concurrent-in-batch write/read workflows are value-checked, recorded
  across two intervals, and serialised with distributions into one JSON file off the event loop.
  `target/benchmark-recorder-postgres.log` records its passing PostgreSQL 18.3 execution. It is explicitly
  integration evidence, not a capacity/degradation claim or a test-first claim for a new database API.
- `target/benchmark-recorder-verify.log` records successful benchmark/dependency verification after
  refactoring the hot-path allocations: all eight selected reactor modules passed in 1:28 at
  14:08:12 +08:00. The 66 fresh Surefire XML reports contain 440 tests, zero failures/errors/skips;
  58 tests belong to the benchmark module. Existing and new PostgreSQL tests ran on 18.3. Log review
  classified the known tooling/schema notices and deliberate Pub/Sub/write-behind fault diagnostics;
  no unexplained test failure was found. Whitespace, banned-pattern and plan-link checks passed.
  No fresh full UI/browser reactor or four-version database matrix is claimed.

At the end of this slice B1 remained incomplete: bounded downstream queues/backpressure, checkpoint cadence, failure recovery,
collection-cost/memory calibration and long-run evidence handling are pending. The current JSON writer
still rewrites an entire retained snapshot; its memory cost is not made bounded merely by adding a
bounded recorder. Existing benchmark commands and HTML generation are unchanged. No campaign runner,
automatic phase transitions or rate-controlled arrivals have been connected yet.

## 16. Implementation evidence — bounded incremental checkpoints (6 September 2026)

### Delivered contract

- `BenchmarkCheckpointWriter` consumes immutable **delta batches**, not cumulative histories. The
  existing schema and single `<executionId>.json` convention are preserved. Every successful
  publication is a complete, independently readable JSON document, including both histories and
  current execution status. Initial identity reservation refuses an existing execution file.
- Explicit limits cover measurement-plus-diagnostic items per batch, encoded batch bytes and distinct
  scenarios. A preflight size check rejects oversized inputs before JSON encoding. Retained state is
  bounded manifest metadata, one continuity tail per scenario, two byte ranges and a SHA-256 digest;
  previous interval/distribution history is never loaded back into the heap. Batch encoding and
  immutable snapshots still allocate; encoded-byte limits are not an exact JVM heap-byte budget.
- History ranges are copied through 8 KiB buffers to a sibling temporary file. Full-file SHA-256
  checks detect external modification between publications without retaining the previous JSON string.
  Same-directory atomic replacement has no non-atomic fallback. Failure leaves writer state unchanged
  and removes its temporary output; the original failure retains cleanup exceptions. This is a
  single-owner API, not coordination with concurrent external writers or fsync/power-loss durability.
- Manifest changes, regressing UTC checkpoint time, counter discontinuity, interval overlap, changed
  scenario units, excessive cardinality and writes after finalisation fail validation. A synchronous
  caller may explicitly retry an unsuccessful append after correcting its cause; reopening an
  interrupted run is not implemented. Unknown phases and outcome/distribution count mismatches retain
  the existing evidence validation.
- `BenchmarkCheckpointPipeline` admits a bounded number of batches and encoded bytes, including the
  active write. It submits one worker drain at a time, not one unbounded worker job per interval.
  Vert.x futures resolve only after publication. Overflow rejects explicitly without dropping or
  coalescing measurements; producers retain responsibility for retry or stopping the experiment.
- A write or worker-submission failure stops admission and fails every accepted pending future with
  the original cause. Terminal submission closes admission; explicit `close()` drains without
  inventing terminal execution status. Worker lifecycle remains caller-owned. Pipeline close must
  precede worker close. A failed persistence path can leave the durable status `RUNNING`, accurately
  reflecting the last successful checkpoint rather than a fabricated failure publication.

### Test-first and integration evidence

- `target/benchmark-checkpoint-red.log`: four new writer tests failed compilation before the new
  implementation existed; `target/benchmark-checkpoint-green.log`: all four passed.
- `target/benchmark-pipeline-red.log`: four new pipeline tests failed compilation before its
  implementation existed; `target/benchmark-pipeline-green.log`: all eight focused tests passed.
  Tests exercise real files and a real single-thread Vert.x worker coordinated by bounded latches,
  not a substituted persistence implementation or mocking framework.
- Coverage includes 201 appended measurement windows with 202 retained diagnostics, UTF-8/quoting,
  terminal status, cardinality/item/byte limits, continuity and immutable manifest protection,
  same-length external file modification, explicit retry, count/byte queue saturation, drain order,
  write-failure fan-out, and already-closed worker submission. Published files and temporary-file
  cleanup are checked, not merely successful future completion.
- Supplemental PostgreSQL 18.3 verification streams forty real value-checked cache workflows through
  the recorder and bounded pipeline in two batches, then finalises and reads the single JSON file.
  `target/benchmark-checkpoint-postgres.log`: ten focused tests, zero failures/errors/skips, including
  both real-PostgreSQL recorder integration cases. This is integration evidence, not a new database
  implementation or a capacity claim.
- `target/benchmark-checkpoint-verify.log`: all eight selected benchmark/dependency reactor modules
  passed in 1:34 at 14:38:23 +08:00. The 68 fresh Surefire reports contained 449 tests, zero
  failures/errors/skips; the benchmark module contained 67 tests. PostgreSQL 18.3 integration ran.
  After tightening the queue-count test to distinguish capacity rejection from closed admission,
  `target/benchmark-checkpoint-final-green.log` re-ran all eight focused tests successfully.
  Log review identified only existing Guice/Unsafe warnings, schema-exists notices and deliberate
  Pub/Sub disconnect/write-behind fault diagnostics. No full UI/browser run or PostgreSQL 15–18
  matrix is claimed for this slice.

### Local recorder-cost probe

A disposable diagnostic probe ran in a separate JDK 25 JShell JVM with one recording thread, three
histogram layouts (16/128/1024 finite buckets), three retained warm-up windows and six measured windows
per layout, each exercising 100,000 logical successful lifecycles. Each lifecycle calls schedule,
admit, start and complete. Paired baseline loops perform four elapsed-clock reads; execution order
alternates. Supplied 1,000/2,000 ns service/end-to-end samples are **synthetic recorder inputs**, never
reported as cache latency. Timings and current-thread allocation readings surround event loops and
checkpoints separately; JSON publication is outside those measured regions. Allocation tracking was
already enabled and was not toggled by the probe.

One checkpointed JSON file contains all 27 raw windows, full synthetic bucket distributions,
configuration, timestamps, local environment, status and limitations:
`target/benchmark-calibration-probe/d2b558f9-e7bb-409a-8c11-64ddb8310a26.json`.
The ignored diagnostic script is `target/recorder-calibration-probe.jsh`; execution output is in
`target/benchmark-calibration-probe.log`. These are local working-tree artifacts, not a shipped
calibration command or release evidence. Re-running needs the current compiled benchmark classpath.

| Finite buckets | Median additional ns/lifecycle over clock baseline | Observed range, ns/lifecycle | Median checkpoint time | Checkpoint allocated bytes |
|---:|---:|---:|---:|---:|
| 16 | 28.535 | 13.551–54.965 | 95.3 µs | 2,816 |
| 128 | 31.962 | 29.211–47.141 | 275.95 µs | 10,880 |
| 1,024 | 39.551 | 35.134–56.161 | 552.9 µs | 75,392 |

The current-thread allocation counter reported zero bytes in all eighteen measured recorder event
loops. This is observed hot-path evidence, not a guarantee for every JVM/outcome/concurrency pattern.
Single-fork results on an uncontrolled development host do not establish uncertainty, production
overhead or limits. Baseline subtraction includes loop differences. Heap retention, concurrent
contention, other outcomes, fork variation and checkpoint pipeline costs need separate calibration.

### Remaining work and cost boundary

At the end of this slice B1 was **not complete**. Automatic time/count checkpoint cadence, restart/recovery classification,
repeatable calibration tooling, constrained-heap/long-run evidence and controlled scheduler coupling
remain pending. Incremental persistence bounds history memory but still copies and hashes the growing
run on each publication: work is O(total published bytes) per checkpoint and can become quadratic
over many equal-sized appends. It needs approximately one extra full-run-sized temporary file while
publishing. Disk budget, cadence and long-run throughput must be measured before selecting this as
the final long-soak strategy. The legacy full-snapshot writer and executable HTML benchmark remain
unchanged; no campaign CLI, automatic phases, rate-controlled arrivals or analysis are claimed.

## 17. Implementation evidence — publication cadence and recovery inspection (6 September 2026)

### Implemented behavior

`BenchmarkCheckpointSession` owns a one-shot Vert.x flush timer and publication lifecycle, using
the existing incremental writer/pipeline. It consumes **already-recorded** interval measurements
and diagnostics; automatic recorder rollover, phase transitions and workload arrivals are separate
work. The initial manifest is empty and `RUNNING`. Configuration is injected: writer limits, a positive
whole-millisecond maximum delay and UTC `Clock`. Resolved limits and delay are retained in reserved
`checkpoint.*` environment fields; user-supplied values under that prefix are rejected, not overwritten.

- Flush when the combined measurement/diagnostic count reaches the configured batch limit, or the
  timer for the oldest staged item fires. Empty sessions do not publish periodic empty checkpoints.
- Retain at most one active batch plus one staging batch. Each batch is constrained by item count
  and encoded bytes; existing scenario-cardinality validation still applies. Futures acknowledge
  publication, not admission. A saturated/oversized candidate rejects without consuming staging
  capacity or dropping earlier accepted items. The producer must retry or stop explicitly.
- A slow active write does not cause repeated timer jobs or unbounded work submission. An elapsed
  staging deadline triggers a flush when the active write ends. The delay is a trigger, not a promise
  that slow storage completes publication by that deadline. Timer identity guards stale callbacks.
- `finish` closes admission, cancels the timer, drains accepted work and explicitly publishes
  completed/failed/stopped status with caller-supplied validity/reason. `stop(reason)` uses STOPPED
  and UNASSESSED. Identical finalisation calls share the same completion future; contradictory calls
  reject. Invalid finalisation metadata leaves the session usable. When accepted data plus a long
  terminal reason exceed the byte limit, preserve the data in a running checkpoint before writing
  terminal metadata separately.
- Persistence failures fail active/staged acknowledgements and session completion with the original
  cause; cleanup failures remain suppressed diagnostics. No successful finalisation is fabricated.
  Await finalisation before closing the caller-owned worker or Vert.x. Close/stop is not a power-loss
  durability guarantee. The disk can still contain the last successfully published RUNNING snapshot.

`BenchmarkCheckpointRecovery.inspect` streams a regular JSON file and returns execution identity,
status, validity, checkpoint time, measurement/diagnostic counts and FINALISED/UNFINALISED classification.
It validates supported version, structural JSON, duplicate fields, required execution metadata and
status/finalised consistency, with parser limits on string size and nesting. It rejects malformed,
truncated, unsupported or contradictory evidence without repairing it. It performs no writes, deletes,
ownership transfer or workload resume. History objects are counted/skipped, not retained as a run-sized
JSON tree; this inspection is not full measurement/accounting/analysis validation or hash verification.

UNFINALISED means owner/liveness review is required. It does **not** establish that the process crashed:
a live owner may continue publishing. FINALISED includes FAILED and STOPPED, not just COMPLETED.
After independently establishing that the old owner has stopped, a new execution must use a fresh
identity; automatic in-place resume and cross-process ownership coordination remain unsupported.

### Verification record

- `target/benchmark-cadence-red.log`: missing session API failed compilation before implementation.
  The initial six tests exercise count/time publication, diagnostics, finalisation, bounded staging,
  failure fan-out, rejected input and invalid cadence before file reservation.
- Log timing review of the first green run found an ignored worker-fixture timeout: a file operation
  had queued behind the deliberately occupied ordered worker context. The fixture now mutates the
  test file before occupying the worker and reports occupancy timeout through the test context.
  `target/benchmark-cadence-fixture-green.log`: all six tests pass in 0.807 seconds, without that timeout.
- `target/benchmark-recovery-red.log`: four recovery tests failed compilation before implementation.
  `target/benchmark-recovery-green.log`: all ten cadence/recovery tests pass. Read-only tests compare
  file bytes before/after inspection and confirm that the existing live writer can still continue.
- Supplemental terminal-byte-boundary verification covers separate publication of a large stop
  reason without losing accepted diagnostics. After timer/callback lifecycle refactoring,
  `target/benchmark-cadence-final-green.log` records 19 focused tests passing: seven session, four
  recovery, four pipeline and four incremental-writer tests; zero failures/errors/skips.
- Supplemental real-PostgreSQL integration records twenty actual value-checked cache workflows,
  waits for automatic timer publication, finalises the run, then inspects that same single JSON
  file as FINALISED. This verifies the composed lifecycle, not capacity or production latency.
- `target/benchmark-cadence-verify.log`: all eight selected benchmark/dependency reactor modules
  passed in 1:36, finishing at 14:47:59 +08:00. The 70 fresh Surefire reports contain 461 tests,
  zero failures/errors/skips; 79 tests belong to the benchmark module. All three real-PostgreSQL
  recorder integration cases ran against PostgreSQL 18.3. The post-review rerun in
  `target/benchmark-cadence-postreview-green.log` passed all 22 checkpoint and PostgreSQL integration
  tests after avoiding redundant timer re-arming for an already-due staged batch.
  Reviewed warnings/errors were the known Guice/Unsafe deprecation, schema-exists notices and
  deliberate Pub/Sub termination/write-behind failure diagnostics. No new UI/browser run or
  four-version PostgreSQL matrix is claimed.

### Remaining boundary

At the end of this slice B1 remained **IN PROGRESS**. Repeatable multi-fork/concurrent calibration, constrained-heap long runs,
disk cost/budget assessment and automatic recorder rollover/scheduler coupling are not complete.
The one-off local probe in §16 is not promoted to a calibrated acceptance campaign. Incremental
checkpoints still copy/hash growing history and may need a different long-soak persistence strategy.
Automatic crash-owner detection, interrupted-run resume, parameter-matrix execution and analysis
remain pending. The legacy benchmark command, browser/UI and screenshot evidence are unchanged.

## 18. Implementation evidence — repeatable recorder/checkpoint calibration (6 September 2026)

### Runnable scope and controls

`BenchmarkCalibrationConfig`, `BenchmarkRecorderCalibration` and `BenchmarkCalibrationMain` replace
the disposable probe as the maintained calibration path. The opt-in `benchmark-calibration` Maven
profile launches a fresh JVM per invocation with explicit maximum heap. It does not alter the legacy
database benchmark/capture profiles. The [runbook](../PEEGEEQ_CACHE_BENCHMARKS.md) lists all properties,
defaults and the repeated-fork command.

The experiment varies recording concurrency, finite histogram bucket count, warm-up/measurement/window
durations, checkpoint batch size, heap and evidence-file budget. Safety bounds are explicit: 1–64
concurrent tasks, 1–65,536 finite buckets, 1–128 pairs per checkpoint, minimum 128 KiB evidence budget,
and representable positive durations. Warm-up may be zero. Window descriptors are lazy; partial final
windows are retained. Planned time is twice the configured recorder time because each pair also runs
a matching-duration baseline. Dispatch, barrier release, snapshots and IO add observable wall time.

Workers share the real recorder and synchronise at a barrier before each loop. Baseline/recorded order
alternates. The baseline performs four elapsed-clock reads per logical iteration; the recorded loop
performs schedule/admit/start/complete, rotating SUCCESS/FAILURE/TIMEOUT and supplying fixed synthetic
1,000/2,000 ns service/end-to-end durations. These distributions exercise recording paths; they are
**not observed cache latency**. Baseline subtraction is not a database instrumentation-overhead estimate.
Per-worker labels denote submitted task slots, not permanently assigned physical threads.

Each run preserves one schema-version-1 JSON file with:

- actual execution UUID, fork index, process ID, requested/resolved calibration settings and units;
- runtime/OS identity, available processors and maximum Java heap (not RSS or total native memory);
- every raw warm-up/measured window, target duration, pair order, per-task operations and elapsed time,
  current-thread allocations when available, observed heap usage and cumulative GC count/time;
- six full histogram series with outcome accounting; interval elapsed time includes the surrounding
  baseline, dispatch and publication gaps rather than pretending to be only hot-loop execution;
- each running checkpoint's write duration and published file bytes, written into the next checkpoint,
  including the terminal publication. Initial/terminal self-write cost is explicitly excluded;
- terminal status, UNASSESSED validity and honest failure/unpublished-observation diagnostics.

No forced GC or enabling of disabled allocation instrumentation occurs. Missing counters have reasons.
Raw timings permit subsequent analysis; no percentile averaging, fabricated zero, statistical acceptance
or universal overhead threshold is introduced. Full host hardware/source provenance and controlled
multi-host campaigns are outside this calibration slice; environmental interference remains possible.

The runner retains one bounded pending batch and fixed-size recorder state. It pumps windows through
callbacks without retaining a future chain or complete history. Running checkpoints conservatively
check the configured file-size budget and reserve 32 KiB for terminal metadata. On a budget failure,
previously published history remains, FAILED finalisation is attempted, and unpublished measurement
counts/latest retained cumulative outcomes are reported. It does not claim to preserve distributions
that could not be published. A physical persistence failure can still leave the last RUNNING checkpoint.
File budget is not reserved free space; atomic replacement temporarily needs both copies on disk.

### Test-first verification and shutdown correction

- `target/benchmark-calibration-red.log`: missing configuration/runner APIs failed compilation before
  implementation. Four initial tests then passed (`benchmark-calibration-green.log`).
- `benchmark-calibration-boundary-red.log`: added assertions exposed doubled-window overflow and
  absent unpublished-observation diagnostics. Both were corrected before the four-test boundary GREEN.
- `benchmark-calibration-fork-red.log`: actual child JVMs failed before the entry point existed.
  Real-process verification then exposed a shutdown defect: a context-bound completion tried to
  dispatch after Vert.x termination, allowing a failed child to fall out with exit code 0.
- `benchmark-calibration-shutdown-red.log` records explicit failing assertions for terminated-executor
  output and missing success notification. The process boundary now bridges into a context-free Vert.x
  promise, closes resources and Vert.x, then reports success or the original failure. The seven-test
  shutdown GREEN verifies two successful 48 MiB child JVMs, invalid configuration exit 2 and budget
  failure exit 1 with preserved FAILED evidence. No exception is swallowed to make shutdown pass.
- `benchmark-calibration-profile-red.log`: the opt-in Maven launch contract failed before its profile
  existed. `benchmark-calibration-final-green.log`: eight tests pass after profile implementation.
  No mocking framework or substitute database is used; calibration itself has no database behavior.
- `target/benchmark-calibration-verify.log`: all eight selected benchmark/dependency reactor modules
  passed in 1:44 at 15:01:15 +08:00. The 74 fresh Surefire reports contain 469 tests, zero failures/
  errors/skips, including 87 benchmark tests and the existing PostgreSQL 18.3 integration cases.
  Logs distinguish the deliberately failed budget child from product/test failures. Known Guice/Unsafe,
  schema-exists and deliberate Pub/Sub/write-behind diagnostics remain; no terminated-executor defect
  remains in acceptance logs. No UI/browser run or four-version PostgreSQL matrix is claimed.

### Bounded-memory endurance evidence

After ordinary verification completed, three sequential invocations of the documented Maven profile
ran without concurrent test/build campaigns. Each used a fresh JVM, 32 MiB maximum heap, four shared
recorder tasks, 1,024 finite buckets, 1,000 ms warm-up, 30,000 ms measured recorder duration, 50 ms
loop windows, ten pairs per checkpoint and a 64 MiB evidence-file budget. Baseline loops add another
31 seconds of targeted time. Each complete Maven invocation took approximately 1:13, ending at
15:03:01, 15:04:16 and 15:05:30 +08:00. This is a bounded-memory endurance experiment, not a production
multi-hour soak or evidence of universally safe deployment settings.

All three files are COMPLETED/UNASSESSED and each retains 20 warm-up pairs, 600 measured pairs and
62 running-write cost records. Every stored recorder-window operation count reconciles with its
cumulative counter delta; all six distribution sample counts reconcile with the corresponding
outcome deltas. No outstanding work remains. Inspection found no missing/extra per-run output or
temporary files. The generated artifacts remain local/ignored:

| Fork | JSON file under `benchmark-results/calibration-b1-20260906/` | Final bytes | Maximum sampled heap bytes | Median first ten writes | Median last ten writes | Maximum write |
|---:|---|---:|---:|---:|---:|---:|
| 0 | `1025a252-e5eb-453c-bb88-00f13a370d82.json` | 37,570,248 | 16,469,552 | 31.616 ms | 199.989 ms | 228.677 ms |
| 1 | `0be3493e-7e54-444f-8c9e-a380aa117c00.json` | 37,569,217 | 15,420,976 | 35.713 ms | 180.116 ms | 216.793 ms |
| 2 | `e01265a5-cbd7-4a00-880d-9f44da0637a3.json` | 37,570,078 | 15,486,512 | 37.730 ms | 187.947 ms | 217.080 ms |

Each final file exceeds the 33,554,432-byte maximum Java heap. Together with successful completion,
that verifies this path does not require loading a complete retained run into that heap. Sampled
heap values are not peak allocations between samples, retained heap after GC, native memory or RSS.
Every fork's final cumulative GC counter was 123; no assertion is made that GC cost is negligible.
The last cumulative scheduled/outcome totals are respectively 51,906,256; 50,587,902; and 54,623,319
synthetic lifecycles, each fully accounted for.

For the 600 non-warm-up pairs, the median difference between recorded and baseline summed-worker
elapsed-time-per-operation was 2,313.788 ns, 2,412.227 ns and 2,126.345 ns per synthetic lifecycle.
These are paired local observations including shared-recorder contention and scheduling effects,
not a universal recorder overhead or a comparison with the earlier single-thread probe's conditions.
Three JVMs reset process state but do not reset filesystem cache, host interference or thermal state.

Write-time growth is a measured instrumentation degradation trend: late publication medians are
approximately five to six times the early medians at this configuration/file size. The writer's
growing copy/hash work is consistent with this observation; the experiment does not isolate every
filesystem/GC contributor or locate a formal onset threshold. Keep write cost visible when connecting
rate-controlled workloads, and do not silently slow offered demand to hide publication pressure.

Execution logs: `target/benchmark-calibration-endurance-fork-{0,1,2}.log`; each contains the explicit
child success notification and successful Maven result, with no exception/OOM signature. The local
read-only diagnostic script `target/analyze-calibration-endurance.ps1` checks counters/distributions
and derives the table after all forks stop, so analysis does not compete with measured loops.

### Remaining boundary

This is recorder/checkpoint calibration, not B2 controlled database load scheduling. B1 remains open
for review of the growing-copy long-soak strategy and integration with product-workload rollover.
Checkpoint time still grows with retained file size; successful calibration does not remove that
behavior. Automatic restart/resume, deployment campaigns, degradation analysis and JSON-derived HTML
remain unimplemented. Nothing in this slice changes UI/screenshots or publishes artifacts externally.

## 19. B2 first slice — bounded workload scheduling and physical accounting (2026-09-06)

`BenchmarkWorkloadScheduler` is a pull-driven state machine, separate from the legacy regression
runner. Construction takes `BenchmarkParameters`, a finite demand duration, an explicit maximum
rate-controlled arrivals-per-advance cap, latency bounds and a monotonic clock. The engine owns no
threads, timers, database connections or output files. Its caller dispatches every returned `Launch`
exactly once, promptly, and reports physical success/failure through `complete(id, succeeded)`.

### Demand and admission policy

- **Closed loop:** each advance fills available physical client slots until the demand horizon.
  Refilling follows actual completion, not logical timeout. The rate-controlled catch-up cap does
  not reduce configured closed-loop concurrency. Driver/dispatch delay affects achieved demand;
  there is no claim of independent offered rate for this mode.
- **Rate controlled:** the first arrival is scheduled at offset zero. The configured decimal rate
  is represented as a rational number; absolute arrival offsets are rounded upward to nanoseconds.
  Completion never shifts subsequent arrivals. Due counts use integer arithmetic, not repeatedly
  adding a rounded period. Construction rejects a schedule/deadline outside supported long ranges.
- **Generator pressure:** each advance considers at most the configured newest tail of due arrivals.
  Older excess arrivals are recorded in bulk as scheduled/rejected and `generatorMissed`, without
  allocating a request per miss. This is an explicit capped-catch-up policy, not a claim of pacing
  fidelity during stalls. At/after the exclusive demand horizon, all previously unobserved arrivals
  are accounted as misses and no new arrivals are launched. Already-admitted queue entries may drain.
- **Admission pressure:** a selected arrival takes a free physical slot, enters the bounded FIFO
  queue, or is rejected. A selected arrival whose deadline has already elapsed is recorded as
  admitted/expired-before-start without creating transport work. Queue capacity excludes active
  physical work; concurrency includes logically timed-out work still physically running.

The maximum schedule-lag metric measures arrival **detection** lateness, including the oldest
arrival subsequently classified as missed. Queue wait is included in end-to-end latency, not this
generator-lag maximum. Catch-up count is configured and must be recorded alongside driver cadence;
it is not a substitute for calibrating generator capacity.

### Logical deadlines versus physical completion

Deadlines are scheduled-arrival time plus operation timeout, including queue wait. Both `advance`
and `complete` detect elapsed deadlines; a late callback cannot become an ordinary success merely
because the polling callback ran late. Detection at the exact deadline is a timeout.

Timeout records one censored logical latency: service from launch to deadline, end-to-end from
scheduled arrival to deadline. It is not the eventual transport duration. The cumulative maximum
deadline-detection lag separately records how late the engine observed expiry. Accounting enters
the actual detection interval; it is not backdated into a previously published interval.

A logically timed-out request retains its physical slot until its transport callback arrives.
That callback increments `lateSucceeded` or `lateFailed` without adding a second logical outcome or
latency sample. Duplicate callbacks are ignored and counted; never-issued IDs are rejected. No
completed-ID history is retained. `drained()` requires both an ended demand horizon and zero queued
or physical work. Zero logical outstanding work alone is insufficient.

### Observation and resource bounds

`checkpoint()` returns the existing recorder sample with actual half-open boundaries and six outcome
distributions. It must run strictly after the previous accounting event; it does not itself generate
demand or detect deadlines. `statistics()` supplies exact cumulative long counters/current physical
and queued populations. Its `metrics()` adapter fits the existing JSON measurement schema: counters
and maxima are cumulative, while physical/queue populations are instantaneous. They are not interval
deltas or rates. Values beyond binary64's contiguous integer precision become explicitly unavailable
numeric metrics with their exact decimal value retained in the reason; logical work counts remain
exact JSON integers.

Retained scheduler state is bounded by concurrency, queue capacity and recorder buckets. A tick scans
physical slots/expired FIFO entries and considers at most the configured arrival cap, irrespective of
the number of skipped arrivals. Rational arithmetic and request/launch allocation are additional
generator costs that still need calibration; the earlier recorder-only figures do not measure them.

### Verification scope and remaining work

Test-first coverage exercises queue saturation, expiry, both load models, fractional scheduling,
exclusive horizons, duplicate/late callbacks, clock/range validation, interval continuity, metric
precision and atomic overflow rejection. A deterministic billion-arrival stall verifies exact missed
demand with only a bounded tail admitted. Real PostgreSQL integration is required before accepting
this slice: SET/GET workflows in both modes, slow PostgreSQL queries crossing logical deadlines,
and incremental publication/recovery inspection of one JSON file. The test driver owns its Vert.x
tick/watchdog and waits for physical drain; it is not the production execution adapter.

B2 was **not complete at this checkpoint**. The managed Vert.x execution adapter described here is
implemented and verified in §22. Retries/attempt identities, explicit per-phase workload transitions,
broader workload mixes, generator calibration and campaign execution remain open. The growing JSON
copy/hash cost recorded in §18 still applies.

### Verification record and environment recovery

`target/benchmark-scheduler-red.log` records the missing scheduler API before implementation.
`target/benchmark-scheduler-green.log` then records 14 passing tests (eight scheduler plus six recorder).
The diagnostics API followed `benchmark-scheduler-metrics-red.log`; its first implementation run
(`benchmark-scheduler-metrics-green.log`) correctly failed two tests because available metrics require
an empty, non-null reason. `benchmark-scheduler-metrics-final-green.log` records the correction.
`benchmark-scheduler-closed-loop-red.log` caught a rate-only catch-up cap incorrectly restricting
closed-loop concurrency; `benchmark-scheduler-closed-loop-green.log` records all 18 focused tests
passing (12 scheduler, six recorder), zero failures/errors/skips, at 15:35:51 +08:00 on 6 September.
The red log's Maven BUILD FAILURE and failing assertion are authoritative; the enclosing shell's
subsequent search command returned zero and is not test-success evidence.

`target/benchmark-scheduler-integration.log` is **not acceptance**: those 18 tests passed but the new
PostgreSQL class failed in setup because Docker was unavailable (19 reported tests, one setup error,
no skips; the three intended integration invocations did not execute). Starting Docker Desktop via
its CLI timed out. Launching the installed application then exposed its backend startup error:
`sailor-ingest.sock` under the user's local Docker runtime directory could not be accessed/removed.
Read-only inspection found a zero-length reparse-point runtime entry. That failed attempt remains
retained as diagnostic history and is not counted as test acceptance.

With user authorisation, Docker was stopped and only the affected local runtime sockets/directories
were moved to timestamped `.stale-*` names; no image, volume, setting or project data was deleted.
Docker Desktop then auto-updated from 4.88.0 to 4.89.0 during the first full gate, restarting its
backend and removing the Docker pipe from live PostgreSQL tests. That interrupted log is retained as
`target/benchmark-scheduler-verify.log`, is not acceptance, and the doomed Maven process was stopped.
The post-update runtime entries were moved once more, 4.89.0 was started as a single owner, and Docker
engine 29.7.2 became healthy.

`target/benchmark-scheduler-integration-green.log` records all 21 focused checks passing: 12 scheduler,
six recorder and three real-PostgreSQL scheduler invocations, zero failures/errors/skips. The database
coverage exercises SET/GET in both load models, incremental JSON finalisation/recovery and real
`pg_sleep` queries crossing logical deadlines while physical concurrency remains bounded.

`target/benchmark-scheduler-verify-final.log` is the acceptance gate: all eight selected modules pass,
with **484 tests in 76 fresh Surefire reports**, zero failures/errors/skips, including 102 benchmark
tests and PostgreSQL 18.3 integration. It completed at 15:55:48 +08:00 on 6 September in 1:35.
Log review found expected schema-exists notices, intentional Pub/Sub administrator-termination
exceptions, the established write-behind persistent-failure test and the intentional calibration
budget-child IOException; no unexpected crash, OOM, terminated executor or promise-double-completion
signature is present. Current benchmark source-pattern and whitespace reviews found no prohibited
mocking, global property mutation, blocking sleeps, alternate futures or disabled tests.
The final gate left zero running containers. One current-run PostgreSQL container stranded in CREATED
state by the Docker auto-update interruption was verified by Testcontainers session labels and removed;
an unrelated older stopped PostgreSQL container was left untouched.

## 20. Jenkins execution boundary (2026-09-06)

The repository now contains a parameterised `Jenkinsfile` and the companion
`PEEGEEQ_CACHE_JENKINS_CI_SETUP.md` runbook. They reuse the sibling PeeGeeQ project's dedicated
`peegeeq-linux` node, native Jenkins/rootful Docker contract, preflight diagnostics, non-concurrent
execution, failure-preserving Maven logs, JUnit publication, artifact retention and workspace cleanup.
Selections cover normal reactor verification, the PostgreSQL 15.17/16.13/17.11/18.3 compatibility
matrix, sequential fresh-JVM recorder calibration and the existing legacy benchmark capture.

This does not change B2's implementation status. Recorder calibration archives comprehensive JSON
per fork, but the database characterisation selection still emits the legacy HTML report. It is
deliberately stored and documented as `legacy-characterisation`, with permissive sentinel performance
gates, so Jenkins can collect observations without implying that the unfinished parameter-matrix
campaign runner exists. The stage must be migrated to per-execution JSON after the managed Vert.x
adapter and campaign entry point are accepted. No Jenkins result may be cited until the reviewed
Jenkinsfile is committed, pushed, selected by the separate `PeeGeeQ-Cache` job and actually executed.

## 21. Status re-verification (2026-09-11)

This pre-implementation snapshot is superseded by the managed-execution evidence in §22.

The implementation was re-checked against source baseline `2de7be1`. The phase table in §8 remains
authoritative: B0, B1 and B2 are **IN PROGRESS**, while B3 through B6 are **NOT STARTED**. No phase is
complete. In particular, `BenchmarkExperiment` resolves bounded run descriptors but does not execute
them, and `BenchmarkWorkloadScheduler` supplies the bounded pull-driven scheduling/accounting state
machine but intentionally owns no threads, timers, transport cancellation or campaign lifecycle.
There is no production connection yet between the experiment/timeline, managed cache operations,
phase transitions, checkpoint session and bounded physical-drain handling.

A fresh benchmark-module gate completed successfully on 11 September 2026:

```text
mvn --batch-mode --no-transfer-progress test -pl peegee-cache-benchmarks
Tests run: 102, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

The 102 checks include real PostgreSQL Testcontainers coverage for pool headroom, recorder publication
and recovery, and both scheduler load models. This verifies the implemented foundation; it is not a
parameterised campaign result or evidence that the plan is complete. Jenkins continues to expose
recorder calibration separately while `benchmark-characterisation` invokes the legacy
`benchmark-capture` profile. The next accepted implementation target therefore remains the B2 managed
execution adapter and its phase, cancellation, drain and live-checkpoint integration described in §19.

## 22. B2 managed Vert.x execution and live checkpoint coupling (2026-09-11)

`BenchmarkManagedExecution` now owns the timers and lifecycle for one resolved run while leaving
Vert.x, the checkpoint worker and the cache/transport operation under caller ownership. It connects
`BenchmarkExperiment.Run`, `BenchmarkTimeline`, `BenchmarkWorkloadScheduler` and
`BenchmarkCheckpointSession`. Separate Vert.x timers drive arrivals, logical-deadline observation,
interval rollover/publication and the bounded physical-drain deadline. Deadline observation does not
generate demand. Each launch is attributed to its active named phase and each actual recorder interval
is published with scheduler diagnostics and all six bounded latency distributions.

The adapter treats operation failures as measured outcomes rather than infrastructure failures. It
supports an explicit idempotent stop request, stops generating new work, waits for admitted physical
operations, and publishes `STOPPED` evidence after they finish. Natural completion publishes
`COMPLETED` evidence only after demand ends and the scheduler physically drains. Exceeding the final
`DRAIN` phase publishes `FAILED`/`INVALID` evidence with the remaining physical population; the
adapter does not pretend a logical timeout cancelled transport work. Timer and checkpoint failures
fail-stop the execution. Final checkpoint admission is checked before terminal metadata is published,
so checkpoint pressure cannot silently discard the last interval.

Strict TDD evidence is retained under the ignored root `target/` directory:

- `benchmark-managed-execution-red.log` records the expected missing-type compilation failure before
  `BenchmarkManagedExecution` existed.
- `benchmark-managed-execution-green.log` records the first three managed lifecycle tests passing.
- `benchmark-managed-execution-integration.log` records four scheduler integration checks passing,
  including managed phase execution of real PostgreSQL-backed cache SET/GET operations and final JSON.
- `benchmark-managed-execution-verify.log` records the complete benchmark-module gate: **108 tests**,
  zero failures/errors/skips, including PostgreSQL 18.3 Testcontainers coverage.
- `benchmark-managed-execution-reactor-verify.log` records the selected eight-module reactor gate:
  **487 tests**, zero failures/errors/skips, with all eight modules successful in 1:41. No
  Testcontainers workload remained running after the gate.

B2 remained **IN PROGRESS at this checkpoint**. Per-phase workload/load transitions and reusable
product-workload definitions are implemented in §23. Retry/attempt identity, generator-cost
calibration and connection of resolved experiment runs to a finite campaign executor remain open.

## 23. B2 per-phase load and workload transitions (2026-09-13)

`BenchmarkPhaseWorkloadPlan` now treats the resolved run's `BenchmarkParameters` as safety ceilings
and requires exactly one ordered profile for every non-drain timeline phase. Each profile declares
target concurrency, target offered rate and an immutable weighted operation mix. Profile names/order
must match the timeline; concurrency and rate cannot exceed the run ceiling; closed-loop phases must
retain zero offered rate; and the timeline must finish with exactly one `DRAIN` phase.

`BenchmarkWorkloadScheduler` accepts the phase plan as an alternative to its backward-compatible
uniform constructor. Closed-loop concurrency changes at planned boundaries without cancelling work
already in flight. Rate-controlled demand uses a piecewise absolute rational schedule: every phase
preserves its declared decimal rate, phase-boundary arrivals are neither lost nor duplicated, and a
delayed driver still applies the existing bounded catch-up/generator-miss policy across transitions.
Deadline observation remains independent and never generates demand.

`BenchmarkWorkloadMix` selects a named operation deterministically from the run seed and logical
request ID, independent of completion order. `BenchmarkProductWorkloads` supplies stable identifiers
for cache SET, GET, SET/GET, DELETE, counter increment, lock acquire/release and scan-page operations,
plus reusable SET/GET and weighted read/write mixes. The managed driver resolves the scheduled phase
and operation before invoking the caller-owned product adapter. It records the complete planned phase
schedule in evidence environment fields and adds active target concurrency/rate, operation weights
and a mixed-transition-window marker to every interval. A request queued across a boundary retains
the phase and workload selected at its scheduled time.

Strict TDD evidence is retained under the ignored root `target/` directory:

- `benchmark-phase-workloads-red.log` records the expected missing contracts, constructor and
  operation-callback compilation failures.
- `benchmark-phase-workloads-green.log` records 22 focused phase-plan, managed-driver and scheduler
  tests passing after the initial implementation.
- `benchmark-phase-workloads-integration.log` records all four scheduler integration checks passing,
  including a real PostgreSQL-backed transition from low-rate SET/GET work to higher-rate GET work.
- `benchmark-phase-workloads-verify.log` records the complete benchmark-module gate: **114 tests**,
  zero failures/errors/skips, including PostgreSQL 18.3 Testcontainers coverage.
- `benchmark-phase-workloads-reactor-verify.log` records the selected eight-module reactor gate:
  **493 tests**, zero failures/errors/skips, with all eight modules successful in 2:03. PostgreSQL
  workload containers and the Testcontainers cleanup helper exited after the gate.

B2 remains **IN PROGRESS**. Logical-operation/physical-attempt identity and bounded retry policy are
implemented in §24. Generator-cost calibration and finite campaign execution remain subsequent B2
work. B3 scenario/diagnostic parameters, B4 analysis, B5 deployment/campaign orchestration and B6
executed characterisation remain unimplemented.

## 24. B2 stable attempt identity and bounded retry policy (2026-09-13)

`BenchmarkRetryPolicy` now defines an explicit positive maximum-attempt budget and a non-negative
whole-millisecond fixed delay. `none()` is the one-attempt policy, so disabled retries remain explicit
in every managed execution configuration. Invalid or sub-millisecond policies fail before a run is
opened.

The managed operation boundary now receives an immutable `Attempt`. Its logical request ID is the
scheduler launch ID; its zero-based attempt index identifies the physical invocation. The original
launch, scheduled phase and deterministically selected product operation are retained across every
retry. A failed physical attempt does not complete or refill the logical scheduler slot while retry
budget and deadline remain. Retries are sequential, timer-owned by the driver and bounded by the
existing concurrency slot, attempt budget and logical deadline. A retry that reaches the deadline is
suppressed and the scheduler records the final physical failure against the already timed-out logical
request. Stop continues draining already admitted retry chains; the final drain deadline cancels
remaining retry timers through the existing fail-stop path.

Evidence records the configured attempt budget/delay and cumulative attempt starts, successes,
failures, scheduled retries, exhausted chains, deadline-suppressed retries and current pending retry
timers. Logical outcome and latency accounting remains in `BenchmarkWorkloadScheduler`; physical
attempt telemetry is additive and does not turn a first-attempt failure followed by success into a
logical failure.

Strict TDD evidence is retained under the ignored root `target/` directory:

- `benchmark-retry-identity-red.log` records the expected missing policy/attempt contracts and
  callback-signature compilation failures before production implementation.
- `benchmark-retry-identity-green.log` records 22 focused policy, managed-driver and scheduler tests
  passing after implementation.
- `benchmark-retry-identity-integration.log` records all four scheduler integration checks passing
  against PostgreSQL 18.3 Testcontainers.
- `benchmark-retry-identity-verify.log` records the complete benchmark-module gate: **116 tests**,
  zero failures/errors/skips.
- `benchmark-retry-identity-reactor-verify.log` records the selected eight-module reactor gate:
  **495 tests**, zero failures/errors/skips, with all eight modules successful. PostgreSQL workload
  containers and the Testcontainers cleanup helper exited after the gate.

B2 remains **IN PROGRESS**. Managed-driver generator calibration is implemented in §25. The next
strict-TDD slice connects resolved runs to a finite campaign executor.

## 25. B2 bounded managed-driver generator calibration (2026-09-13)

`BenchmarkGeneratorCalibration` now executes a finite, strictly increasing list of offered rates
through `BenchmarkManagedExecution`. Its configuration bounds the sweep to 64 rates, caps concurrency
and arrivals considered per advance, and requires positive whole-millisecond measurement, drain,
sampling and arrival intervals with cadence contained by the measurement window. Every rate runs as
its own resolved rate-controlled experiment and produces a separate terminal evidence file.

The calibration adapter completes a synthetic `generator.noop` operation immediately. Evidence is
labelled `GENERATOR_CALIBRATION` and states that it contains no cache or PostgreSQL capacity
measurement. Each returned observation records offered rate, first-attempt dispatch count, generator
misses, admission rejections, maximum schedule-detection lag, terminal status and evidence path. The
ordered raw observations expose the first rate at which this driver/JVM/host combination produces
misses without inventing a universal pass threshold.

Managed evidence now also records cumulative and maximum synchronous phase-selection,
workload-selection and operation-dispatch time, plus its sample count. Together with the existing
absolute schedule-lag and generator-miss metrics, this separates measured generator pressure from
product-operation completion latency. Checkpoint publication remains active during the sweep so its
real interference is visible rather than silently removed.

Strict TDD evidence is retained under the ignored root `target/` directory:

- `benchmark-generator-calibration-red.log` records the expected missing calibration contract before
  production implementation.
- `benchmark-generator-calibration-green.log` records eight focused calibration and managed-driver
  tests passing.
- `benchmark-generator-calibration-verify.log` records the complete benchmark-module gate:
  **118 tests**, zero failures/errors/skips, including PostgreSQL 18.3 Testcontainers coverage.
- `benchmark-generator-calibration-reactor-verify.log` records the selected eight-module reactor
  gate: **497 tests**, zero failures/errors/skips, with every module successful in 1:45. PostgreSQL
  containers and the Testcontainers cleanup helper exited after the gate.

B2 is completed by the finite resolved-run executor in §26. B3 scenario/diagnostic parameters, B4
analysis, B5 deployment orchestration and B6 executed characterisation remain unimplemented.

## 26. B2 finite resolved-run campaign execution (2026-09-13)

`BenchmarkCampaignExecutor` now bridges a budget-checked `BenchmarkExperiment` to
`BenchmarkManagedExecution` without materialising or silently expanding the matrix. It resolves runs
in the experiment's declared configuration/fork/repetition order and executes exactly one at a time.
An additional campaign execution budget is checked before the first preparation callback.

The caller-owned asynchronous `Preparer` is the reset/provisioning boundary for each resolved run. It
returns the empty RUNNING manifest, managed options and operation adapter. The executor verifies that
the prepared manifest contains the exact resolved run before opening evidence. Preparation or
infrastructure failure fails the campaign future while already finalised files remain intact;
terminal managed outcomes, including measured FAILED results, are retained as ordered run results.
The executor does not own PostgreSQL, Vert.x or the shared checkpoint worker.

Strict TDD evidence is retained under the ignored root `target/` directory:

- `benchmark-campaign-executor-red.log` records the expected missing executor contract.
- `benchmark-campaign-executor-green.log` records 11 focused experiment, managed-driver and campaign
  tests passing.
- `benchmark-campaign-executor-verify.log` records the complete benchmark-module gate: **120 tests**,
  zero failures/errors/skips, including PostgreSQL 18.3 Testcontainers coverage.
- `benchmark-campaign-executor-reactor-verify.log` records the selected eight-module reactor gate:
  **499 tests**, zero failures/errors/skips, with every module successful in 1:48. PostgreSQL
  containers and the Testcontainers cleanup helper exited after the gate.

B2 is **COMPLETE**. B3's first immutable scenario contract and real product adapter are implemented in
§27. B0/B1 remain in progress for their broader contracts and long-soak persistence decision; B4
through B6 have not started.

## 27. B3 deterministic data scenario and real SET/GET adapter (2026-09-13)

`BenchmarkScenarioParameters` introduces bounded immutable controls for dataset cardinality, exact
binary payload bytes, uniform or hot-set key selection, hot-set size/request fraction, entry TTL and
telemetry mode. Dataset cardinality and payload size have explicit safety ceilings. TTL is disabled by
zero or otherwise requires positive whole milliseconds. Uniform distributions cannot smuggle in hot
parameters; hot sets must be a proper dataset subset with a finite fraction in `(0,1]`.

Key selection is a pure function of recorded seed and logical request ID, so physical retries retain
the same key. Payload generation is a pure function of seed and key index and returns the exact
declared byte count. The scenario exports all controls as stable `scenario.*` environment fields for
merging into the initial run manifest before evidence is opened.

`BenchmarkCacheSetGetOperation` is the first real product adapter. It implements the managed operation
boundary using the public `CacheService`, performs UPSERT followed by GET, applies the configured TTL,
and verifies that PostgreSQL returns the expected bytes. Unsupported workload identifiers fail
explicitly. The adapter does not mock transport, serialization or persistence.

Strict TDD evidence is retained under the ignored root `target/` directory:

- `benchmark-scenario-contract-red.log` records the expected missing scenario and adapter contracts.
- `benchmark-scenario-evidence-red.log` records the later missing evidence-environment contract.
- `benchmark-scenario-contract-green.log` records seven focused unit/integration checks, including
  five PostgreSQL scheduler cases and exact bounded-key, 129-byte payload and TTL verification.
- `benchmark-scenario-contract-verify.log` records the complete benchmark-module gate: **123 tests**,
  zero failures/errors/skips.
- `benchmark-scenario-contract-reactor-verify.log` records the selected eight-module reactor gate:
  **502 tests**, zero failures/errors/skips, with every module successful in 1:50. PostgreSQL
  containers and the Testcontainers cleanup helper exited after the gate.

B3 remains **IN PROGRESS**. Explicit hit/miss semantics and the complete cache adapter family are
implemented in §28. Counter/lock/scan controls and capability diagnostics remain next.

## 28. B3 prepared cache operation family and hit/miss semantics (2026-09-13)

`BenchmarkScenarioParameters` now includes an explicit finite target hit ratio in `[0,1]`. The hit or
miss decision is deterministic from the recorded seed and logical request ID, so retries cannot
change the intended outcome. The ratio is retained as `scenario.targetHitRatio`; zero and one are
handled exactly without probabilistic edge behavior.

`BenchmarkCacheOperation` binds the stable cache GET, SET, DELETE and SET/GET identifiers to the public
`CacheService`. `prepareDataset()` populates the declared hit working set before measurement in
bounded batches of 256 requests rather than materialising the full cardinality. GET selects either a
prepared key or a disjoint miss key and verifies the observed presence/absence; hit and SET/GET paths
also verify exact deterministic bytes. SET applies the configured TTL and DELETE is measured through
the actual service. Unknown non-cache identifiers fail explicitly. The earlier focused SET/GET class
now delegates to this common adapter so the semantics cannot drift.

Strict TDD evidence is retained under the ignored root `target/` directory:

- `benchmark-cache-operation-family-red.log` records missing hit-ratio and adapter-family contracts.
- `benchmark-cache-operation-family-green.log` records eight focused checks, including six real
  PostgreSQL integration cases covering preparation, intended hit/miss, SET and verified DELETE.
- `benchmark-cache-operation-family-verify.log` records the complete benchmark-module gate:
  **124 tests**, zero failures/errors/skips.
- `benchmark-cache-operation-family-reactor-verify.log` records the selected eight-module reactor
  gate: **503 tests**, zero failures/errors/skips, with every module successful in 1:46. PostgreSQL
  containers and the Testcontainers cleanup helper exited after the gate.

B3 remains **IN PROGRESS**. Counter contention and correctness are implemented in §29. Lock and scan
workload families plus capability diagnostics remain.

## 29. B3 counter contention and exact correctness (2026-09-13)

`BenchmarkCounterParameters` defines bounded counter cardinality, a proper hot-counter subset, the
fraction of requests directed to that subset, a positive increment delta and an initial value. Key
selection is deterministic from the recorded seed and logical request ID, and every parameter is
exported as a stable `scenario.*` environment field. Construction rejects invalid fractions and
configurations whose initial aggregate or first increment cannot be represented exactly as `long`.

`BenchmarkCounterOperation` binds `counter.increment` to the public `CounterService`. It initializes
the declared working set before measurement in bounded batches of 256, applies real atomic
`incrementBy` operations, and provides an overflow-safe post-run aggregate check across every counter.
The final check requires all prepared counters to remain present and compares the observed sum with
`initialValue * cardinality + delta * successfulAttempts`; unsupported operations fail explicitly.

Strict TDD evidence is retained under the ignored root `target/` directory:

- `benchmark-counter-contention-red.log` records the expected missing parameter and adapter contracts.
- `benchmark-counter-contention-green.log` records nine focused checks, including seven PostgreSQL
  cases and a 40-operation hot-key contention run with exact final aggregate verification.
- `benchmark-counter-contention-verify.log` records the complete benchmark-module gate: **127 tests**,
  zero failures/errors/skips.
- `benchmark-counter-contention-reactor-verify.log` records the selected eight-module reactor gate:
  **506 tests**, zero failures/errors/skips, with every module successful in 1:49. PostgreSQL
  containers and the Testcontainers cleanup helper exited after the gate.

B3 remains **IN PROGRESS**. Lock contention and fencing are implemented in §30. Scan workload
parameters and capability diagnostics remain.

## 30. B3 lock contention, fencing and cleanup (2026-09-13)

`BenchmarkLockParameters` defines bounded lock cardinality, a proper hot-lock subset, deterministic
hot-request fraction, positive whole-millisecond lease duration and explicit fencing mode. Selection
is stable from the run seed and logical request ID, and all controls are exported as `scenario.*`
evidence fields.

`BenchmarkLockOperation` binds `lock.acquire-release` to the public `LockService`. Each physical
attempt receives a distinct owner token derived from logical request ID and attempt index. A denied
acquisition is counted as observed contention rather than a transport failure. Successful acquisitions
validate returned key/owner identity and fencing presence, reject non-increasing tokens for the same
key, and release using the exact owner. Thread-safe counters retain acquisitions, contention,
releases and fencing tokens. A bounded post-run scan proves no benchmark-owned lease remains.

Strict TDD evidence is retained under the ignored root `target/` directory:

- `benchmark-lock-contention-red.log` records the expected missing parameter and adapter contracts.
- `benchmark-lock-contention-green.log` records ten focused checks, including eight PostgreSQL cases
  and a 40-attempt hot-lock run that observed acquisition and contention, reconciled releases/fencing,
  and verified an empty final lock set.
- `benchmark-lock-contention-verify.log` records the complete benchmark-module gate: **130 tests**,
  zero failures/errors/skips.
- `benchmark-lock-contention-reactor-verify.log` records the selected eight-module reactor gate:
  **509 tests**, zero failures/errors/skips, with every module successful in 1:49. PostgreSQL
  containers and the Testcontainers cleanup helper exited after the gate.

B3 remains **IN PROGRESS**. The next strict-TDD slice is deterministic scan-page workload parameters,
real cursor traversal and completeness/duplicate verification, followed by capability diagnostics.

## 31. B3 completion — scan, runtime safety and capability inventory (2026-09-13)

`BenchmarkScanParameters` bounds page size to the product limit and records value/expired-entry and
prefix choices. `BenchmarkScanOperation` prepares deterministic data, traverses real PostgreSQL
cursors to completion, rejects cursor cycles, duplicate/unexpected/omitted keys and terminal cursors,
and optionally verifies every payload. The integration gate traversed 23 entries in five pages.

`BenchmarkRuntimePolicy` evaluates explicit heap, process-CPU and event-loop-delay limits. Missing
required observations are failures with an unavailable reason, never zero. The versioned
`BenchmarkCapabilityInventory` distinguishes RUNNABLE, UNSUPPORTED and OBSERVATION_UNAVAILABLE.
Cache, counter, lock and scan are runnable in the managed local path. Managed Pub/Sub, expiry-churn
and authorised HA-restart extensions remain explicitly unsupported; portable database-host CPU and
storage latency remain unavailable. This honest inventory completes B3 without pretending those
optional extensions ran.

Strict-TDD evidence is retained in `target/benchmark-scan-{red,green}.log` and
`target/benchmark-runtime-capabilities-{red,green}.log`. The RED boundaries failed because the new
types/adapters did not exist. GREEN includes parameter tests and real PostgreSQL cursor traversal.

## 32. B4 completion — versioned trend analysis and JSON-derived HTML (2026-09-13)

`BenchmarkAnalysisPolicy` versions reference selection, minimum samples, latency effect size,
persistence, recovery, adverse-outcome and pressure-growth rules. `BenchmarkTrendAnalyzer` emits
candidate onset and confirmation offsets, measured brackets, maximum progression and recovery for
persistent p95 deterioration; persistent adverse outcomes and growing queue pressure are separate
findings. Isolated latency spikes remain visible without being classified as persistent change.
Missing/low-count reference evidence produces INCONCLUSIVE, while absence of a qualifying onset is
limited to the tested range and timeframe.

`BenchmarkAnalysisPublisher` atomically inserts analysis into the authoritative final JSON and
derives a self-contained HTML view containing the execution identity, analysis status, embedded
result and authoritative JSON SHA-256. It refuses unfinalised evidence. The first real campaign
exposed a synthetic-fixture naming error (`successfulServiceLatency` versus the recorder's canonical
`successfulService`); `target/benchmark-real-schema-analysis-red.log` records the new failing
contract and the corresponding GREEN proves both analyzer and review consume the real schema.
Additional known-trace RED/GREEN evidence is in `target/benchmark-analysis-*` and
`target/benchmark-detectors-*`.

## 33. B0/B1 closure — complete specification and bounded persistence decision (2026-09-13)

`BenchmarkSpecification` now resolves the immutable timeline, complete finite load matrix,
repetitions/forks/seed, deterministic data scenario, runtime safety policy, evidence destination,
capability inventory and persistence policy before launch. Its JSON manifest retains all resolved
configurations and phase durations. Existing interval boundaries, counters, outcome distributions,
retry identities and per-phase workload transition fields remain the run-level evidence contract.

The selected version-1 long-run persistence decision deliberately retains atomic comprehensive JSON
rather than introducing a second authoritative store. `BenchmarkPersistencePolicy` estimates final
bytes, cumulative growing-copy bytes and the approximately two-copy peak disk requirement from the
finite interval count and checkpoint grouping. A campaign whose estimate exceeds any declared bound
is rejected before workload launch. This does not remove the writer's measured O(growing-history)
publication cost and does not claim unbounded soak support; it turns that cost into an explicit,
reviewable support boundary. B1 is complete for finite budgeted runs under this policy. A future
segmented format would be a new policy/schema version, not an implicit change to existing evidence.

RED/GREEN records: `target/benchmark-spec-persistence-{red,green}.log`. The full resolved manifest
for the accepted local campaign is retained as `benchmark-results/characterisation-framework-final-20260913/resolved-experiment.json`.

## 34. B5 completion — target-safe campaign orchestration (2026-09-13)

`BenchmarkDeploymentTarget` has distinct local-Testcontainers and external identities and contains no
credentials. External connections have explicit host, port, database, schema prefix and TLS policy;
server ownership is always false. `BenchmarkPostgresTargetVerifier` performs read-only database,
server-version and session-TLS verification without closing or restarting the supplied pool.

`BenchmarkCampaignPlan` expands a finite declared or seeded-random order and assigns a unique bounded
resource name to every configuration/fork/repetition. `BenchmarkCampaignRunner` verifies the target,
publishes a live atomic campaign manifest, resets before each run, invokes managed execution,
cleans the run-owned resource, analyzes its JSON and records its HTML path. Cleanup is attempted after
post-reset preparation failure; cleanup failure is suppressed behind the primary failure and the
FAILED manifest is retained. `BenchmarkLocalPostgresCampaignAdapter` scopes destructive SQL to the
run-owned namespace and never owns its supplied pool/server.

The real PostgreSQL gate verifies an externally supplied Testcontainers endpoint and successfully
queries it afterward. A real local campaign run then proves its owned namespace is empty after cleanup.
Lifecycle, external-path and failure-path evidence is in `target/benchmark-deployment-plan-*`,
`target/benchmark-campaign-runner-*`, `target/benchmark-external-target-*`,
`target/benchmark-local-campaign-*` and `target/benchmark-campaign-cleanup-*`.

## 35. B6 local framework-acceptance characterisation (2026-09-13)

The opt-in `benchmark-characterisation` profile and `BenchmarkLocalCampaignMain` execute the resolved
campaign against disposable PostgreSQL, then publish `characterisation-review.json` from retained run
JSON. The review accounts for planned/completed/loaded runs, retains per-run interval curves, onset
brackets when present, min/max p95 variation by configuration, limitations and follow-ups.

The accepted local framework campaign used PostgreSQL 18.3-alpine, cache GET/SET weight 4:1, 100
deterministic 256-byte entries, concurrency/pool 4, offered rates 100 and 200 requests/second, three
repetitions per rate, 1,000 ms each for baseline/load/recovery, a 2,000 ms drain and 250 ms sampling.
All six of six runs completed, were analyzed and cleaned. The review status is
`COMPLETE_NO_ONSET_OBSERVED`; this means only that no version-1 persistent symptom was detected in
this short local range/timeframe. It is not production capacity, a release limit or proof of stability.
All six final executions are `COMPLETED` and measurement-validity `VALID`. Repeat peak-p95 ranges
were 5 ms–10 ms at both configurations,
which is retained variation rather than averaged away. The sole campaign-level limitation states
that the disposable local target is not external production evidence.

Authoritative ignored artifacts are under
`benchmark-results/characterisation-framework-final-20260913/`: six UUID JSON results, their six
self-contained HTML derivatives, `campaign-manifest.json`, `resolved-experiment.json` and
`characterisation-review.json`. Execution evidence is
`target/benchmark-characterisation-final-artifacts.log` (`BUILD SUCCESS`). Every HTML contains actual
successful-service-p95 and successful-rate SVG curves and the matching authoritative JSON hash.

## 36. Final verification and scope boundary (2026-09-13)

`target/benchmark-module-complete-gate.log` recorded 151 benchmark tests before the final detector,
cleanup and validity cases. `target/benchmark-final-reactor-after-validity.log` is the definitive gate:
all eleven modules succeeded in 6:07, with 713 Java tests and 168 management-UI tests (881 total),
zero failures/errors/skips. The benchmark module contributed 155 tests, including eleven real-
PostgreSQL scheduler/scenario/campaign tests. Source review found no Mockito use and
`git diff --check` reported no whitespace errors. After real SVG time-series rendering was added,
`target/benchmark-post-html-final-gate.log` passed the seven benchmark/dependency modules in 1:35:
534 Java tests, zero failures/errors/skips.

B0–B5 framework implementation is complete. B6 is complete for the declared local acceptance
campaign. External target execution, PostgreSQL-version matrices, multi-host diagnostics, managed
Pub/Sub/expiry/fault extensions and longer/higher-load campaigns are future explicitly scoped work;
their absence cannot be converted into a production claim. Generated measurements remain ignored
and local until the owner chooses an archive/publication destination.

## 37. Current-codebase documentation audit (2026-09-15)

The completion statements above were checked against committed revision `f983355`. The audit
confirmed the local `benchmark-characterisation` Maven profile and `BenchmarkLocalCampaignMain`,
the implemented cache/counter/lock/scan adapters, runtime/capability/persistence policies, campaign
orchestration, analysis/report publishing, 46 benchmark test classes and all 15 retained final-campaign
artifacts. No generic external Maven profile or credential-loading external main class exists; only
the caller-supplied external target/verifier path is implemented and tested.

A fresh non-Docker benchmark contract run passed 130 tests with zero failures, errors or skips. A
fresh selected-reactor integration attempt could not reach the benchmark module because this host had
no valid Docker/Testcontainers environment; the failure occurred while starting PostgreSQL tests and
does not supersede the successful definitive integration evidence recorded in §36. A new live
PostgreSQL acceptance result therefore requires Docker to be available.
