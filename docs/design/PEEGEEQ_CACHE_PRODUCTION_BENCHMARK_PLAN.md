# Parameterised performance and degradation analysis plan

**Revised:** 15 September 2026
**Last reconciled:** 24 September 2026 against `3ed1162` plus the uncommitted 24 September `Jenkinsfile` run-mode rename (dated implementation evidence §12–§36 moved to the [implementation log](archive/PEEGEEQ_CACHE_BENCHMARK_IMPLEMENTATION_LOG_2026-09.md))
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

Follow the [benchmark runbook](../PEEGEEQ_CACHE_BENCHMARKS.md),
[TDD standard](../guidelines/PEEGEEQ_CACHE_TEST_COVERAGE_AND_TDD_APPROACH.md), and applicable coding,
configuration, isolation and Vert.x rules in the
[development guidelines](../guidelines/PEEGEEQ_CACHE_DEV_GUIDELINES.md). The archived [implementation plan](archive/PEEGEEQ_CACHE_IMPLEMENTATION_PLAN.md) that first framed this work is a historical record only.

Implement test-first in small slices: inspect RED, implement, inspect GREEN, then refactor. No Mockito
or substitute mocking framework. Database tests use real PostgreSQL through Testcontainers. Pure
statistics and timing-policy tests use explicit sample streams and injected time, not fabricated
database performance. Inject configuration; do not mutate JVM system properties in tests. Preserve
Vert.x Future composition, bounded ownership, primary failures and cleanup diagnostics.

The legacy regression harness that existed before B0 needed more than external-database connectivity. Every gap below was closed by B0–B6 (§8); the table records why the framework is shaped the way it is. The legacy harness itself is unchanged and still backs the `benchmark` and `benchmark-capture` profiles.

| Legacy harness (before B0) | Limitation | Change delivered by B0–B6 |
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

This table replaces the phase definitions in the archived implementation plan. Production target selection is not an entry gate.

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
archive. Do not publish or commit bulk data without instruction. Update the runbook and this plan
when capabilities and verification change; distinguish planned options from runnable ones.

Framework completion means B0–B5 capabilities and verification are delivered, not that all deployments
have been measured. Campaign completion means its declared finite matrix, repetitions and analysis
are accounted for, including observed degradation and inconclusive outcomes. Keep release-readiness
open until B6 evidence for the declared scope is reviewed. Degradation is a finding, not automatically
a failed benchmark or a reason to weaken thresholds.

## 11. Next work and open items

The implementation sequence is complete for the declared local framework scope ([current status](#current-status-and-evidence); evidence in the [implementation log](archive/PEEGEEQ_CACHE_BENCHMARK_IMPLEMENTATION_LOG_2026-09.md) §§31–36). The next work is not an unimplemented framework phase: it is an owner-declared external campaign or a separately versioned detector/scenario extension. Before either, choose the target, authority, finite matrix, persistence budgets and required diagnostics. Automatic in-place restart/resume is deliberately unsupported; begin a new execution identity after independent owner/liveness review. There is no generic `benchmark-external` Maven profile or credential-loading external entry point in the current codebase. The first external campaign must supply a caller-owned pool to the implemented target verifier/campaign runner, or introduce a reviewed campaign-specific launcher.

Open items:

| Id | Item | Origin |
|---|---|---|
| JENKINS-JSON-CAMPAIGN | Add a Jenkins run mode for the JSON characterisation campaign (`-Pbenchmark-characterisation`) that archives the complete evidence directory. Jenkins currently runs only the legacy HTML capture, renamed from `benchmark-characterisation` to `legacy-benchmark-capture` on 24 September 2026 so it no longer shares a name with the Maven profile. | Implementation log §20; [Jenkins setup](PEEGEEQ_CACHE_JENKINS_CI_SETUP.md#current-characterisation-boundary) |
| SCENARIO-FAMILIES | The only executed campaign is the two-rate local cache GET/SET campaign, which covers a small part of §7 family 1 (load sensitivity). Families 2 (contention/data), 3 (duration), 4 (burst/recovery), 5 (instrumentation) and 6 (faults) have no executed campaign, although B3 delivered counter, lock and scan adapters for them; managed Pub/Sub, expiry-churn and HA-restart extensions are unsupported. | §7; implementation log §§31, 36 |
| EXTERNAL-CAMPAIGN | No external or production-representative campaign has been run; see above. | §9 |
| RETAINED-EVIDENCE | The accepted campaign's evidence is under the git-ignored `benchmark-results/`; its execution logs were copied into the git-ignored `logs/` on 24 September 2026 (see [current status](#current-status-and-evidence)). An owner-chosen archive destination is still open. | §10 |

## Current status and evidence

B0–B5 framework implementation is complete and B6 is complete for the declared local acceptance campaign (§8). The retained campaign is described in the [benchmark runbook](../PEEGEEQ_CACHE_BENCHMARKS.md#parameterised-characterisation-campaign), including the exact command that reproduces its configuration.

Evidence, all local and git-ignored:

- the accepted campaign: `benchmark-results/characterisation-framework-final-20260913/` (six UUID JSON results, six derived HTML reports, `resolved-experiment.json`, `campaign-manifest.json`, `characterisation-review.json`), produced by the run logged in `logs/benchmark-characterisation-final-artifacts-20260913.log`;
- the reactor run cited as the "definitive gate" in the implementation log §36: `logs/benchmark-final-reactor-after-validity-20260913.log`. It ran Surefire and the Vitest suite across all ten modules plus the parent (the "eleven modules" in Maven's reactor summary) on PostgreSQL 18.3 in 6:07, with 713 Java and 168 UI tests and zero failures, errors or skips. It did **not** run the Failsafe browser catalogue; the latest complete `verify` including Failsafe is recorded in the [coverage matrix §3](PEEGEEQ_CACHE_FUNCTIONALITY_COVERAGE_MATRIX.md#3-current-result);
- the benchmark-module gates `logs/benchmark-module-complete-gate-20260913.log` and `logs/benchmark-post-html-final-gate-20260913.log`.

The three other `characterisation-framework-*-20260913` directories under `benchmark-results/` are earlier runs from the same afternoon (created at 18:21, 18:23 and 18:36; the accepted run at 18:46) and are not cited as evidence: `characterisation-framework-20260913` used 100 ms sampling and its review is `COMPLETE_INCONCLUSIVE`; `characterisation-framework-complete-20260913` and `characterisation-framework-valid-20260913` used 250 ms sampling and report `COMPLETE_NO_ONSET_OBSERVED`.

## Current-codebase documentation audit (2026-09-15)

The completion statements above were checked against committed revision `f983355`. The audit
confirmed the local `benchmark-characterisation` Maven profile and `BenchmarkLocalCampaignMain`,
the implemented cache/counter/lock/scan adapters, runtime/capability/persistence policies, campaign
orchestration, analysis/report publishing, 46 benchmark test classes and all 15 retained final-campaign
artifacts. No generic external Maven profile or credential-loading external main class exists; only
the caller-supplied external target/verifier path is implemented and tested.

A fresh non-Docker benchmark contract run passed 130 tests with zero failures, errors or skips. A
fresh selected-reactor integration attempt could not reach the benchmark module because this host had
no valid Docker/Testcontainers environment; the failure occurred while starting PostgreSQL tests and
does not supersede the successful integration evidence recorded in the implementation log §36. A new live
PostgreSQL acceptance result therefore requires Docker to be available.
