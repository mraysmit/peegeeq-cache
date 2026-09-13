package dev.mars.peegeeq.cache.benchmark;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.WorkerExecutor;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Managed Vert.x driver for one resolved benchmark run. It owns arrival, deadline-observation,
 * sampling and drain timers, while the caller owns Vert.x, the checkpoint worker and transport.
 * Operation failures are measured outcomes. Driver/checkpoint failures fail the completion future.
 */
public final class BenchmarkManagedExecution {
    @FunctionalInterface
    public interface Operation {
        Future<Void> execute(Attempt attempt);
    }

    public record Attempt(long logicalRequestId, int attemptIndex, BenchmarkWorkloadScheduler.Launch launch,
                          BenchmarkTimeline.Phase phase, String productOperation) {
        public Attempt {
            if (logicalRequestId < 0) throw new IllegalArgumentException("Logical request ID must not be negative");
            if (attemptIndex < 0) throw new IllegalArgumentException("Attempt index must not be negative");
            Objects.requireNonNull(launch, "launch");
            if (logicalRequestId != launch.id()) {
                throw new IllegalArgumentException("Logical request ID must match launch ID");
            }
            Objects.requireNonNull(phase, "phase");
            productOperation = named(productOperation, "product operation");
        }
    }

    public record Options(String scenario, String operationUnit, int maximumArrivalsPerAdvance,
                          List<Long> latencyBoundsNanos, Duration arrivalInterval,
                          Duration deadlineObservationInterval,
                          BenchmarkCheckpointWriter.Limits checkpointLimits,
                          Duration checkpointMaximumDelay,
                          BenchmarkPhaseWorkloadPlan phaseWorkloads,
                          BenchmarkRetryPolicy retryPolicy) {
        public Options {
            scenario = named(scenario, "scenario");
            operationUnit = named(operationUnit, "operation unit");
            if (maximumArrivalsPerAdvance <= 0) {
                throw new IllegalArgumentException("Maximum arrivals per advance must be positive");
            }
            latencyBoundsNanos = List.copyOf(latencyBoundsNanos);
            new BenchmarkLatencyDistribution(latencyBoundsNanos,
                    java.util.Collections.nCopies(latencyBoundsNanos.size(), 0L), 0);
            wholePositiveMillis(arrivalInterval, "arrival interval");
            wholePositiveMillis(deadlineObservationInterval, "deadline observation interval");
            Objects.requireNonNull(checkpointLimits, "checkpointLimits");
            wholePositiveMillis(checkpointMaximumDelay, "checkpoint maximum delay");
            Objects.requireNonNull(phaseWorkloads, "phaseWorkloads");
            Objects.requireNonNull(retryPolicy, "retryPolicy");
        }
    }

    public record Result(Path path, BenchmarkRunEvidence.Status status,
                         BenchmarkRunEvidence.Validity validity, String detail,
                         BenchmarkWorkloadScheduler.Statistics statistics) {
        public Result {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(validity, "validity");
            Objects.requireNonNull(detail, "detail");
            Objects.requireNonNull(statistics, "statistics");
        }
    }

    public static final class Execution {
        private final Driver driver;

        private Execution(Driver driver) { this.driver = driver; }

        public Path path() { return driver.session.path(); }
        public Future<Result> completion() { return driver.completion.future(); }
        public Future<Result> stop(String reason) { return driver.stop(reason); }
    }

    private BenchmarkManagedExecution() { }

    public static Future<Execution> start(Vertx vertx, WorkerExecutor checkpointWorker, Path outputDirectory,
                                          BenchmarkRunEvidence initial, Options options, Operation operation) {
        try {
            Objects.requireNonNull(vertx, "vertx");
            Objects.requireNonNull(checkpointWorker, "checkpointWorker");
            Objects.requireNonNull(outputDirectory, "outputDirectory");
            Objects.requireNonNull(initial, "initial");
            Objects.requireNonNull(options, "options");
            Objects.requireNonNull(operation, "operation");
            if (initial.status() != BenchmarkRunEvidence.Status.RUNNING
                    || !initial.measurements().isEmpty() || !initial.diagnostics().isEmpty()) {
                throw new IllegalArgumentException("Managed execution requires an empty RUNNING manifest");
            }
            validatePhasePlan(initial.plan(), options.phaseWorkloads());
            TimelineBounds bounds = timelineBounds(initial.plan().timeline());
            var manifest = withExecutionEnvironment(initial, options);
            return BenchmarkCheckpointSession.open(vertx, checkpointWorker, outputDirectory, manifest,
                    options.checkpointLimits(), options.checkpointMaximumDelay(), Clock.systemUTC()).map(session -> {
                var driver = new Driver(vertx, session, manifest, options, operation, bounds);
                vertx.runOnContext(ignored -> driver.begin());
                return new Execution(driver);
            });
        } catch (RuntimeException invalid) {
            return Future.failedFuture(invalid);
        }
    }

    private record TimelineBounds(Duration demand, Duration drain) { }

    private static void validatePhasePlan(BenchmarkExperiment.Run run, BenchmarkPhaseWorkloadPlan phasePlan) {
        var timeline = run.timeline();
        var planned = phasePlan.timeline();
        if (!run.parameters().equals(phasePlan.ceiling()) || !timeline.phases().equals(planned.phases())
                || !timeline.samplingInterval().equals(planned.samplingInterval())) {
            throw new IllegalArgumentException("Phase workload plan must match the resolved run");
        }
    }

    private static BenchmarkRunEvidence withExecutionEnvironment(BenchmarkRunEvidence initial, Options options) {
        var plan = options.phaseWorkloads();
        var environment = new LinkedHashMap<>(initial.environment());
        if (environment.keySet().stream().anyMatch(key -> key.startsWith("workload.phase.")
                || key.startsWith("retry."))) {
            throw new IllegalArgumentException("workload.phase.* and retry.* environment fields are reserved");
        }
        environment.put("retry.maximumAttempts", Integer.toString(options.retryPolicy().maximumAttempts()));
        environment.put("retry.delayMillis", Long.toString(options.retryPolicy().delay().toMillis()));
        for (var profile : plan.profiles()) {
            String prefix = "workload.phase." + profile.phase() + ".";
            environment.put(prefix + "concurrency", Integer.toString(profile.concurrency()));
            environment.put(prefix + "offeredPerSecond", Double.toString(profile.offeredPerSecond()));
            environment.put(prefix + "mix", profile.workload().weights().stream()
                    .map(weight -> weight.operation() + "=" + weight.weight())
                    .collect(java.util.stream.Collectors.joining(",")));
        }
        return new BenchmarkRunEvidence(initial.executionId(), initial.plan(), initial.startedAt(), initial.updatedAt(),
                initial.status(), initial.validity(), initial.detail(), environment, List.of(), List.of());
    }

    private static TimelineBounds timelineBounds(BenchmarkTimeline timeline) {
        Objects.requireNonNull(timeline, "timeline");
        long demandNanos = 0;
        BenchmarkTimeline.Phase drain = null;
        for (var phase : timeline.phases()) {
            if (phase.kind() == BenchmarkTimeline.PhaseKind.DRAIN) {
                if (drain != null || phase != timeline.phases().getLast()) {
                    throw new IllegalArgumentException("Managed execution requires exactly one final DRAIN phase");
                }
                drain = phase;
            } else if (drain != null) {
                throw new IllegalArgumentException("No demand phase may follow DRAIN");
            } else {
                demandNanos = Math.addExact(demandNanos, phase.duration().toNanos());
            }
        }
        if (drain == null || demandNanos <= 0) {
            throw new IllegalArgumentException("Managed execution requires demand followed by DRAIN");
        }
        return new TimelineBounds(Duration.ofNanos(demandNanos), drain.duration());
    }

    private static final class Driver {
        private final Vertx vertx;
        private final BenchmarkCheckpointSession session;
        private final BenchmarkRunEvidence initial;
        private final Options options;
        private final Operation operation;
        private final TimelineBounds bounds;
        private final BenchmarkWorkloadScheduler scheduler;
        private final Promise<Result> completion = Promise.promise();
        private long arrivalTimer = -1;
        private long deadlineTimer = -1;
        private long sampleTimer = -1;
        private long drainTimer = -1;
        private final Set<Long> retryTimers = new HashSet<>();
        private long attemptStarted;
        private long attemptSucceeded;
        private long attemptFailed;
        private long retriesScheduled;
        private long retriesExhausted;
        private long retriesDeadlineSuppressed;
        private long selectionDispatchNanosTotal;
        private long selectionDispatchNanosMaximum;
        private long selectionDispatchCount;
        private int retriesPending;
        private volatile boolean stopping;
        private volatile boolean ended;
        private String stopReason;

        private Driver(Vertx vertx, BenchmarkCheckpointSession session, BenchmarkRunEvidence initial,
                       Options options, Operation operation, TimelineBounds bounds) {
            this.vertx = vertx;
            this.session = session;
            this.initial = initial;
            this.options = options;
            this.operation = operation;
            this.bounds = bounds;
            this.scheduler = new BenchmarkWorkloadScheduler(initial.plan().parameters(), options.phaseWorkloads(),
                    options.maximumArrivalsPerAdvance(), options.latencyBoundsNanos(), System::nanoTime);
        }

        private void begin() {
            if (ended) return;
            try {
                arrivalTimer = vertx.setPeriodic(millis(options.arrivalInterval()), ignored -> drive());
                deadlineTimer = vertx.setPeriodic(millis(options.deadlineObservationInterval()), ignored -> observe());
                sampleTimer = vertx.setPeriodic(millis(initial.plan().timeline().samplingInterval()), ignored -> sample());
                drainTimer = vertx.setTimer(millis(initial.plan().timeline().duration()), ignored -> drainExpired());
                drive();
            } catch (Throwable failure) {
                infrastructureFailure(failure);
            }
        }

        private void drive() {
            if (ended || stopping) return;
            try {
                for (var launch : scheduler.advance()) dispatch(launch);
                completeIfDrained();
            } catch (Throwable failure) {
                infrastructureFailure(failure);
            }
        }

        private void observe() {
            if (ended) return;
            try {
                scheduler.observe();
                completeIfDrained();
            } catch (Throwable failure) {
                infrastructureFailure(failure);
            }
        }

        private void dispatch(BenchmarkWorkloadScheduler.Launch launch) {
            long started = System.nanoTime();
            try {
                var phase = phaseAt(launch.scheduledNanos());
                var profile = options.phaseWorkloads().profileAt(launch.scheduledNanos());
                String selected = profile.workload().select(initial.plan().workloadSeed(), launch.id());
                attempt(launch, phase, selected, 0);
            } finally {
                long elapsed = System.nanoTime() - started;
                selectionDispatchNanosTotal = Math.addExact(selectionDispatchNanosTotal, elapsed);
                selectionDispatchNanosMaximum = Math.max(selectionDispatchNanosMaximum, elapsed);
                selectionDispatchCount = Math.incrementExact(selectionDispatchCount);
            }
        }

        private void attempt(BenchmarkWorkloadScheduler.Launch launch, BenchmarkTimeline.Phase phase,
                             String productOperation, int attemptIndex) {
            if (ended) return;
            attemptStarted = Math.incrementExact(attemptStarted);
            Future<Void> result;
            try {
                result = Objects.requireNonNull(operation.execute(
                                new Attempt(launch.id(), attemptIndex, launch, phase, productOperation)),
                        "Operation returned null future");
            } catch (Throwable failure) {
                result = Future.failedFuture(failure);
            }
            result.onComplete(outcome -> vertx.runOnContext(ignored -> attemptCompleted(
                    launch, phase, productOperation, attemptIndex, outcome.succeeded())));
        }

        private void attemptCompleted(BenchmarkWorkloadScheduler.Launch launch, BenchmarkTimeline.Phase phase,
                                      String productOperation, int attemptIndex, boolean succeeded) {
            if (ended) return;
            try {
                if (succeeded) {
                    attemptSucceeded = Math.incrementExact(attemptSucceeded);
                    scheduler.complete(launch.id(), true);
                } else {
                    attemptFailed = Math.incrementExact(attemptFailed);
                    if (options.retryPolicy().allowsRetryAfter(attemptIndex) && scheduler.retryable(launch.id())) {
                        scheduleRetry(launch, phase, productOperation, attemptIndex + 1);
                        return;
                    }
                    if (!options.retryPolicy().allowsRetryAfter(attemptIndex)) {
                        retriesExhausted = Math.incrementExact(retriesExhausted);
                    } else {
                        retriesDeadlineSuppressed = Math.incrementExact(retriesDeadlineSuppressed);
                    }
                    scheduler.complete(launch.id(), false);
                }
                completeIfDrained();
            } catch (Throwable failure) {
                infrastructureFailure(failure);
            }
        }

        private void scheduleRetry(BenchmarkWorkloadScheduler.Launch launch, BenchmarkTimeline.Phase phase,
                                   String productOperation, int attemptIndex) {
            retriesScheduled = Math.incrementExact(retriesScheduled);
            retriesPending = Math.incrementExact(retriesPending);
            long delayMillis = options.retryPolicy().delay().toMillis();
            if (delayMillis == 0) {
                vertx.runOnContext(ignored -> runRetry(launch, phase, productOperation, attemptIndex));
                return;
            }
            final long[] timerReference = new long[1];
            long timer = vertx.setTimer(delayMillis, ignored -> {
                retryTimers.remove(timerReference[0]);
                runRetry(launch, phase, productOperation, attemptIndex);
            });
            timerReference[0] = timer;
            retryTimers.add(timer);
        }

        private void runRetry(BenchmarkWorkloadScheduler.Launch launch, BenchmarkTimeline.Phase phase,
                              String productOperation, int attemptIndex) {
            if (ended) return;
            retriesPending--;
            try {
                if (scheduler.retryable(launch.id())) {
                    attempt(launch, phase, productOperation, attemptIndex);
                } else {
                    retriesDeadlineSuppressed = Math.incrementExact(retriesDeadlineSuppressed);
                    scheduler.complete(launch.id(), false);
                    completeIfDrained();
                }
            } catch (Throwable failure) {
                infrastructureFailure(failure);
            }
        }

        private void sample() {
            if (ended) return;
            try {
                publish(scheduler.checkpoint());
            } catch (Throwable failure) {
                infrastructureFailure(failure);
            }
        }

        private void publish(BenchmarkIntervalRecorder.Sample sample) {
            session.append(measurement(sample)).onFailure(this::infrastructureFailure);
        }

        private BenchmarkTimeline.Phase phaseAt(long elapsedNanos) {
            var timeline = initial.plan().timeline();
            long bounded = Math.min(elapsedNanos, timeline.duration().toNanos() - 1);
            return timeline.windowAt(Math.max(0, bounded)).orElseThrow().phase();
        }

        private void completeIfDrained() {
            if (ended) return;
            if (stopping) {
                if (scheduler.statistics().physicalInFlight() == 0) {
                    finish(BenchmarkRunEvidence.Status.STOPPED, BenchmarkRunEvidence.Validity.UNASSESSED, stopReason);
                }
            } else if (scheduler.drained()) {
                finish(BenchmarkRunEvidence.Status.COMPLETED, BenchmarkRunEvidence.Validity.VALID, "");
            }
        }

        private synchronized Future<Result> stop(String reason) {
            if (reason == null || reason.isBlank()) return Future.failedFuture("Stop reason must not be blank");
            if (ended) return completion.future();
            if (stopReason != null) {
                return stopReason.equals(reason) ? completion.future()
                        : Future.failedFuture("Different stop already requested");
            }
            stopReason = reason;
            stopping = true;
            vertx.runOnContext(ignored -> {
                if (ended) return;
                cancel(arrivalTimer);
                arrivalTimer = -1;
                cancel(drainTimer);
                drainTimer = vertx.setTimer(millis(bounds.drain()), timer -> drainExpired());
                completeIfDrained();
            });
            return completion.future();
        }

        private void drainExpired() {
            if (ended) return;
            try {
                scheduler.observe();
                if (stopping && scheduler.statistics().physicalInFlight() == 0) {
                    completeIfDrained();
                    return;
                }
                if (!stopping && scheduler.drained()) {
                    completeIfDrained();
                    return;
                }
                finish(BenchmarkRunEvidence.Status.FAILED, BenchmarkRunEvidence.Validity.INVALID,
                        "Physical drain deadline exceeded with " + scheduler.statistics().physicalInFlight()
                                + " operation(s) still in flight");
            } catch (Throwable failure) {
                infrastructureFailure(failure);
            }
        }

        private void finish(BenchmarkRunEvidence.Status status, BenchmarkRunEvidence.Validity validity, String detail) {
            if (ended) return;
            ended = true;
            cancelTimers();
            try {
                var sample = scheduler.checkpoint();
                var measurement = measurement(sample);
                var accepted = session.append(measurement);
                if (accepted.failed()) {
                    status = BenchmarkRunEvidence.Status.FAILED;
                    validity = BenchmarkRunEvidence.Validity.INVALID;
                    detail = "Final checkpoint was rejected: " + accepted.cause();
                }
                var result = new Result(session.path(), status, validity, detail, scheduler.statistics());
                session.finish(status, validity, detail)
                        .onSuccess(ignored -> completion.tryComplete(result))
                        .onFailure(completion::tryFail);
            } catch (Throwable failure) {
                session.finish(BenchmarkRunEvidence.Status.FAILED, BenchmarkRunEvidence.Validity.INVALID,
                                "Managed execution failed while finalising: " + failure)
                        .onComplete(ignored -> completion.tryFail(failure));
            }
        }

        private void infrastructureFailure(Throwable failure) {
            if (ended) return;
            finish(BenchmarkRunEvidence.Status.FAILED, BenchmarkRunEvidence.Validity.INVALID,
                    "Managed execution infrastructure failure: " + failure);
        }

        private BenchmarkRunEvidence.Measurement measurement(BenchmarkIntervalRecorder.Sample sample) {
            var metrics = new LinkedHashMap<>(scheduler.statistics().metrics());
            metrics.put("retry.attemptStarted", metric("count", attemptStarted));
            metrics.put("retry.attemptSucceeded", metric("count", attemptSucceeded));
            metrics.put("retry.attemptFailed", metric("count", attemptFailed));
            metrics.put("retry.retriesScheduled", metric("count", retriesScheduled));
            metrics.put("retry.retriesExhausted", metric("count", retriesExhausted));
            metrics.put("retry.deadlineSuppressed", metric("count", retriesDeadlineSuppressed));
            metrics.put("retry.pending", metric("count", retriesPending));
            metrics.put("generator.selectionDispatchNanosTotal", metric("ns", selectionDispatchNanosTotal));
            metrics.put("generator.selectionDispatchNanosMaximum", metric("ns", selectionDispatchNanosMaximum));
            metrics.put("generator.selectionDispatchCount", metric("count", selectionDispatchCount));
            var phase = phaseAt(sample.interval().startNanos());
            metrics.put("load.transitionMixed", metric("boolean",
                    options.phaseWorkloads().crossesTransition(sample.interval().startNanos(),
                            sample.interval().endNanos()) ? 1 : 0));
            if (phase.kind() == BenchmarkTimeline.PhaseKind.DRAIN) {
                metrics.put("load.targetConcurrency", metric("count", 0));
                metrics.put("load.targetOfferedPerSecond", metric("operations/second", 0));
            } else {
                var profile = options.phaseWorkloads().profileAt(sample.interval().startNanos());
                metrics.put("load.targetConcurrency", metric("count", profile.concurrency()));
                metrics.put("load.targetOfferedPerSecond", metric("operations/second", profile.offeredPerSecond()));
                for (var weight : profile.workload().weights()) {
                    metrics.put("workload.weight." + weight.operation(), metric("weight", weight.weight()));
                }
            }
            return new BenchmarkRunEvidence.Measurement(options.scenario(), options.operationUnit(),
                    phase.name(), sample.interval(), Map.copyOf(metrics), sample.distributions());
        }

        private static BenchmarkRunEvidence.Metric metric(String unit, double value) {
            return new BenchmarkRunEvidence.Metric(unit, value, "");
        }

        private void cancelTimers() {
            cancel(arrivalTimer);
            cancel(deadlineTimer);
            cancel(sampleTimer);
            cancel(drainTimer);
            for (long retryTimer : retryTimers) cancel(retryTimer);
            retryTimers.clear();
            retriesPending = 0;
            arrivalTimer = deadlineTimer = sampleTimer = drainTimer = -1;
        }

        private void cancel(long timer) {
            if (timer != -1) vertx.cancelTimer(timer);
        }
    }

    private static long wholePositiveMillis(Duration duration, String field) {
        Objects.requireNonNull(duration, field);
        long nanos;
        try { nanos = duration.toNanos(); }
        catch (ArithmeticException overflow) { throw new IllegalArgumentException(field + " exceeds nanosecond range", overflow); }
        if (nanos < 1_000_000 || nanos % 1_000_000 != 0) {
            throw new IllegalArgumentException(field + " must be positive whole milliseconds");
        }
        return nanos / 1_000_000;
    }

    private static long millis(Duration duration) {
        long nanos = duration.toNanos();
        return 1 + (nanos - 1) / 1_000_000;
    }

    private static String named(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
