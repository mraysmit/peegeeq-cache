package dev.mars.peegeeq.cache.benchmark;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.WorkerExecutor;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static dev.mars.peegeeq.cache.benchmark.BenchmarkRunEvidence.*;

/** Time-windowed, concurrent recorder/IO calibration. No cache or database performance is measured. */
public final class BenchmarkRecorderCalibration {
    private BenchmarkRecorderCalibration() { }

    public static Future<Path> run(Vertx vertx, Path directory, BenchmarkCalibrationConfig config, int forkIndex) {
        if (forkIndex < 0) return Future.failedFuture(new IllegalArgumentException("Negative fork identity"));
        var state = new State(vertx, config, forkIndex);
        return state.io.executeBlocking(() -> {
            state.writer = BenchmarkCheckpointWriter.create(directory, state.evidence(Status.RUNNING, ""), config.writerLimits());
            if (Files.size(state.writer.path()) + State.TERMINAL_RESERVE > config.maximumEvidenceBytes()) {
                throw new IOException("Evidence budget cannot retain initial manifest and terminal reserve");
            }
            return null;
        }).compose(ignored -> state.execute()).transform(result -> state.finish(result.cause()))
                .eventually(() -> state.workers.close().eventually(state.io::close));
    }

    private record Work(long operations, long elapsedNanos, long allocatedBytes) { }

    private static final class State {
        static final long TERMINAL_RESERVE = 32_768;
        private static final BenchmarkIntervalRecorder.Outcome[] OUTCOMES = BenchmarkIntervalRecorder.Outcome.values();
        final Vertx vertx;
        final BenchmarkCalibrationConfig config;
        final WorkerExecutor workers;
        final WorkerExecutor io;
        final UUID executionId = UUID.randomUUID();
        final Instant started = Instant.now();
        final long origin = System.nanoTime();
        final BenchmarkIntervalRecorder recorder;
        final BenchmarkExperiment.Run plan;
        final Map<String, String> environment;
        final ArrayList<Measurement> pending = new ArrayList<>();
        final Promise<Void> done = Promise.promise();
        final AtomicLong clockSink = new AtomicLong();
        List<Diagnostic> terminalDiagnostics = List.of();
        BenchmarkCheckpointWriter writer;
        long windowIndex;
        long writes;
        long lastWriteEnd;
        String phase = "observe";

        State(Vertx vertx, BenchmarkCalibrationConfig config, int forkIndex) {
            this.vertx = vertx;
            this.config = config;
            workers = vertx.createSharedWorkerExecutor("recorder-calibration-" + executionId, config.concurrency());
            io = vertx.createSharedWorkerExecutor("calibration-io-" + executionId, 1);
            var bounds = java.util.stream.LongStream.rangeClosed(1, config.bucketCount()).map(n -> n * 1000).boxed().toList();
            recorder = new BenchmarkIntervalRecorder(bounds, () -> System.nanoTime() - origin);
            var phases = new ArrayList<BenchmarkTimeline.Phase>();
            if (!config.warmup().isZero()) phases.add(new BenchmarkTimeline.Phase("warmup", BenchmarkTimeline.PhaseKind.WARMUP, config.warmup().multipliedBy(2)));
            phases.add(new BenchmarkTimeline.Phase("observe", BenchmarkTimeline.PhaseKind.SUSTAINED, config.measurement().multipliedBy(2)));
            var timeline = new BenchmarkTimeline(phases, config.window().multipliedBy(2));
            var parameters = new BenchmarkParameters(BenchmarkParameters.LoadModel.CLOSED_LOOP, config.concurrency(), 1, 0, 0, Duration.ofSeconds(30));
            plan = new BenchmarkExperiment.Run(1, "recorder-calibration", 0, forkIndex, 0, 0, parameters, timeline);
            var env = new HashMap<String, String>();
            env.put("purpose", "RECORDER_CALIBRATION");
            env.put("scope", "Shared recorder contention and checkpoint costs; no PostgreSQL or cache capacity measurement");
            env.put("syntheticLatencies", "service=1000ns,endToEnd=2000ns; success/failure/timeout round robin; NOT observed cache latencies");
            env.put("method", "Alternating baseline/recorded order; baseline four clock reads; per-worker loops synchronised at barrier");
            env.put("timeframes", "Each pair targets two configured windows; actual intervals include dispatch, baseline and checkpoint gaps; warmup retained");
            env.put("configuration.poolSize", "Placeholder only; this calibration opens no database pool");
            env.put("configuration.operationTimeout", "Worker start-barrier safety deadline only; not a measured-operation timeout");
            env.put("bucketCount", Integer.toString(config.bucketCount()));
            env.put("bucketStepNanos", "1000");
            env.put("warmupNanos", Long.toString(config.warmup().toNanos()));
            env.put("measurementNanos", Long.toString(config.measurement().toNanos()));
            env.put("windowNanos", Long.toString(config.window().toNanos()));
            env.put("checkpointWindows", Integer.toString(config.checkpointWindows()));
            env.put("maximumEvidenceBytes", Long.toString(config.maximumEvidenceBytes()));
            env.put("maximumBatchBytes", Integer.toString(config.writerLimits().maximumBatchBytes()));
            env.put("terminalReserveBytes", Long.toString(TERMINAL_RESERVE));
            env.put("writeCostScope", "Running publications only; initial/terminal self-write cost not included; filesystem cache not forced to stable storage");
            env.put("javaRuntime", System.getProperty("java.runtime.version"));
            env.put("javaVm", System.getProperty("java.vm.name"));
            env.put("os", System.getProperty("os.name"));
            env.put("osVersion", System.getProperty("os.version"));
            env.put("processId", Long.toString(ProcessHandle.current().pid()));
            env.put("maximumHeapBytes", Long.toString(Runtime.getRuntime().maxMemory()));
            env.put("availableProcessors", Integer.toString(Runtime.getRuntime().availableProcessors()));
            environment = Map.copyOf(env);
        }

        Future<Void> execute() { next(); return done.future(); }

        void next() {
            if (windowIndex == config.windowCount()) { done.complete(); return; }
            var window = config.windowAt(windowIndex);
            phase = window.phase();
            boolean baselineFirst = windowIndex % 2 == 0;
            loop(!baselineFirst, window.durationNanos()).compose(first -> loop(baselineFirst, window.durationNanos()).map(second -> {
                var baseline = baselineFirst ? first : second;
                var recorded = baselineFirst ? second : first;
                long start = System.nanoTime();
                var sample = recorder.checkpoint();
                long checkpointNanos = System.nanoTime() - start;
                var metrics = new HashMap<String, Metric>();
                metrics.put("baselineFirst", metric("boolean 0/1", baselineFirst ? 1 : 0));
                metrics.put("targetLoopNanos", metric("nanoseconds", window.durationNanos()));
                metrics.put("recorderCheckpointNanos", metric("nanoseconds", checkpointNanos));
                addWork(metrics, "baseline", baseline);
                addWork(metrics, "recorded", recorded);
                metrics.put("heap.usedBytes", metric("bytes", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed()));
                metrics.put("gc.collectionCount", gc(false));
                metrics.put("gc.collectionMillis", gc(true));
                pending.add(new Measurement("recorder-calibration", "synthetic-lifecycle", phase, sample.interval(), metrics, sample.distributions()));
                return null;
            })).compose(ignored -> {
                windowIndex++;
                return windowIndex % config.checkpointWindows() == 0 || windowIndex == config.windowCount()
                        ? publish() : Future.succeededFuture();
            }).onSuccess(ignored -> vertx.runOnContext(event -> next())).onFailure(done::tryFail);
        }

        Future<List<Work>> loop(boolean record, long nanos) {
            var barrier = new CyclicBarrier(config.concurrency());
            var futures = new ArrayList<Future<Work>>();
            for (int worker = 0; worker < config.concurrency(); worker++) {
                futures.add(workers.executeBlocking(() -> {
                    barrier.await(30, TimeUnit.SECONDS);
                    long allocation = allocated();
                    long begin = System.nanoTime();
                    long operations = 0;
                    long checksum = 0;
                    while (System.nanoTime() - begin < nanos) {
                        if (record) {
                            recorder.schedule(); recorder.admit(); recorder.start();
                            recorder.complete(OUTCOMES[(int) (operations % 3)], 1000, 2000);
                        } else {
                            checksum ^= System.nanoTime() - origin; checksum ^= System.nanoTime() - origin;
                            checksum ^= System.nanoTime() - origin; checksum ^= System.nanoTime() - origin;
                        }
                        operations++;
                    }
                    long elapsed = System.nanoTime() - begin;
                    long after = allocated();
                    clockSink.addAndGet(checksum);
                    return new Work(operations, elapsed, allocation < 0 || after < 0 ? -1 : after - allocation);
                }, false));
            }
            return Future.join(futures).map(ignored -> futures.stream().map(Future::result).toList());
        }

        Future<Void> publish() {
            return io.executeBlocking(() -> {
                var evidence = evidence(Status.RUNNING, "");
                checkBudget(evidence, TERMINAL_RESERVE);
                long begin = System.nanoTime();
                writer.append(evidence);
                long end = System.nanoTime();
                long bytes = Files.size(writer.path());
                long previous = writes++;
                var interval = new BenchmarkInterval(lastWriteEnd, end - origin, counts(previous), counts(writes));
                lastWriteEnd = end - origin;
                pending.clear();
                pending.add(new Measurement("checkpoint-publication", "checkpoint", phase, interval,
                        Map.of("writeNanos", metric("nanoseconds", end - begin), "publishedBytes", metric("bytes", bytes))));
                return null;
            });
        }

        Future<Path> finish(Throwable cause) {
            if (writer == null) return Future.failedFuture(cause);
            return io.executeBlocking(() -> {
                if (cause != null) {
                    var summary = new io.vertx.core.json.JsonObject().put("unpublishedMeasurements", pending.size())
                            .put("completedWindowPairs", windowIndex)
                            .put("reason", "Unpublished observations cannot be assumed durable; previously published history is preserved");
                    pending.stream().filter(item -> item.scenario().equals("recorder-calibration")).reduce((first, last) -> last)
                            .ifPresent(item -> summary.put("lastUnpublishedCumulativeScheduled", item.interval().after().scheduled())
                                    .put("lastUnpublishedCumulativeSucceeded", item.interval().after().succeeded())
                                    .put("lastUnpublishedCumulativeFailed", item.interval().after().failed())
                                    .put("lastUnpublishedCumulativeTimedOut", item.interval().after().timedOut()));
                    terminalDiagnostics = List.of(new Diagnostic(System.nanoTime() - origin, "ERROR", summary.encode()));
                    pending.clear();
                }
                var evidence = evidence(cause == null ? Status.COMPLETED : Status.FAILED,
                        cause == null ? "Calibration observations collected; not a capacity or overhead acceptance claim" : cause.toString());
                checkBudget(evidence, 0);
                writer.append(evidence);
                return writer.path();
            }).transform(result -> {
                if (cause == null) return result.succeeded() ? Future.succeededFuture(result.result()) : Future.failedFuture(result.cause());
                if (result.failed() && cause != result.cause()) cause.addSuppressed(result.cause());
                return Future.failedFuture(cause);
            });
        }

        void checkBudget(BenchmarkRunEvidence evidence, long reserve) throws IOException {
            long added = writer.prepare(evidence).bytes;
            long current = Files.size(writer.path());
            if (current > config.maximumEvidenceBytes() - reserve - added) throw new IOException("Calibration evidence byte budget exceeded");
        }

        BenchmarkRunEvidence evidence(Status status, String detail) {
            return new BenchmarkRunEvidence(executionId, plan, started, Instant.now(), status, Validity.UNASSESSED,
                    detail, environment, pending, terminalDiagnostics);
        }

        static void addWork(Map<String, Metric> metrics, String label, List<Work> values) {
            long total = 0;
            for (int index = 0; index < values.size(); index++) {
                var value = values.get(index);
                total += value.operations();
                String key = label + ".worker." + index + ".";
                metrics.put(key + "operations", metric("synthetic-lifecycles", value.operations()));
                metrics.put(key + "elapsedNanos", metric("nanoseconds", value.elapsedNanos()));
                metrics.put(key + "allocatedBytes", value.allocatedBytes() < 0 ? unavailable("bytes", "Thread allocation tracking unavailable or disabled")
                        : metric("bytes", value.allocatedBytes()));
            }
            metrics.put(label + ".operations", metric("synthetic-lifecycles", total));
        }

        static long allocated() {
            if (ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean
                    && bean.isThreadAllocatedMemorySupported() && bean.isThreadAllocatedMemoryEnabled()) {
                return bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
            }
            return -1;
        }

        static Metric gc(boolean millis) {
            long sum = 0;
            for (var bean : ManagementFactory.getGarbageCollectorMXBeans()) {
                long value = millis ? bean.getCollectionTime() : bean.getCollectionCount();
                if (value < 0) return unavailable(millis ? "milliseconds" : "collections", "Collector does not report this counter");
                sum += value;
            }
            return metric(millis ? "milliseconds" : "collections", sum);
        }
        static Metric metric(String unit, long value) { return new Metric(unit, (double) value, ""); }
        static Metric unavailable(String unit, String reason) { return new Metric(unit, null, reason); }
        static BenchmarkWorkCounts counts(long count) { return new BenchmarkWorkCounts(count, count, count, count, 0, 0, 0, 0); }
    }
}
