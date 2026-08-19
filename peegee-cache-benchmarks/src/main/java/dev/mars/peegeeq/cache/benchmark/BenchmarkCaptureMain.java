package dev.mars.peegeeq.cache.benchmark;

import io.vertx.core.Future;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Java-first entry point for repeated benchmarks and reproducible evidence capture. */
public final class BenchmarkCaptureMain {

    private static final DateTimeFormatter ID_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC);

    private BenchmarkCaptureMain() {
    }

    public static void main(String[] args) {
        run(BenchmarkCaptureConfig.fromSystemProperties(), BenchmarkConfig.fromSystemProperties())
                .onSuccess(System::exit)
                .onFailure(failure -> {
                    failure.printStackTrace(System.err);
                    System.exit(1);
                });
    }

    static Future<Integer> run(
            BenchmarkCaptureConfig captureConfig, BenchmarkConfig benchmarkConfig) {
        Instant captureStarted = Instant.now();
        final BenchmarkEnvironment environment;
        final String benchmarkId;
        final Path reportFile;
        try {
            environment = BenchmarkEnvironment.capture(
                    captureConfig.repositoryRoot(), captureConfig.topology(),
                    captureConfig.postgresImage(), benchmarkConfig);
            benchmarkId = ID_TIME.format(captureStarted) + "-" + shortCommit(environment);
            Files.createDirectories(captureConfig.resolvedOutputRoot());
            reportFile = captureConfig.resolvedOutputRoot().resolve(benchmarkId + ".html");
            System.out.println("Benchmark evidence: " + reportFile.toAbsolutePath());

            if (captureConfig.requireCleanGit() && !environment.workingTreeClean()) {
                BenchmarkCaptureReport rejected = new BenchmarkCaptureReport(
                        benchmarkId, "rejected-dirty-working-tree", captureStarted, Instant.now(),
                        captureConfig.runs(), List.of());
                BenchmarkEvidenceWriter.write(reportFile, rejected, environment);
                System.err.println("Working tree is dirty; benchmark capture was rejected.");
                return Future.succeededFuture(2);
            }
        } catch (Throwable failure) {
            return Future.failedFuture(failure);
        }

        List<CapturedBenchmarkRun> runs = new ArrayList<>();
        return captureRuns(1, captureConfig, benchmarkConfig, benchmarkId,
                        captureStarted, reportFile, environment, runs)
                .compose(ignored -> finishCapture(captureConfig, benchmarkConfig,
                        benchmarkId, captureStarted, reportFile, runs));
    }

    private static Future<Void> captureRuns(
            int runNumber, BenchmarkCaptureConfig captureConfig, BenchmarkConfig benchmarkConfig,
            String benchmarkId, Instant captureStarted, Path reportFile,
            BenchmarkEnvironment environment, List<CapturedBenchmarkRun> runs) {
        if (runNumber > captureConfig.runs()) {
            return Future.succeededFuture();
        }
        Instant started = Instant.now();
        return captureOutput(() -> CacheBenchmarkMain.run(benchmarkConfig))
                .compose(execution -> {
                    CapturedBenchmarkRun captured = execution.failure() == null
                            ? CapturedBenchmarkRun.passed(runNumber, started, Instant.now(),
                                    execution.result(), execution.log())
                            : CapturedBenchmarkRun.failed(runNumber, started, Instant.now(),
                                    execution.failure(), execution.log());
                    runs.add(captured);
                    String interimStatus = "passed".equals(captured.status()) ? "running" : "failed";
                    try {
                        BenchmarkEvidenceWriter.write(reportFile, new BenchmarkCaptureReport(
                                benchmarkId, interimStatus, captureStarted, Instant.now(),
                                captureConfig.runs(), runs), environment);
                    } catch (Throwable failure) {
                        return Future.failedFuture(failure);
                    }
                    if (!"passed".equals(captured.status()) && captureConfig.stopOnFailure()) {
                        return Future.succeededFuture();
                    }
                    return captureRuns(runNumber + 1, captureConfig, benchmarkConfig,
                            benchmarkId, captureStarted, reportFile, environment, runs);
                });
    }

    private static Future<Integer> finishCapture(
            BenchmarkCaptureConfig captureConfig, BenchmarkConfig benchmarkConfig,
            String benchmarkId, Instant captureStarted, Path reportFile,
            List<CapturedBenchmarkRun> runs) {
        try {
            BenchmarkEnvironment finalEnvironment = BenchmarkEnvironment.capture(
                    captureConfig.repositoryRoot(), captureConfig.topology(),
                    captureConfig.postgresImage(), benchmarkConfig);
            boolean passed = runs.size() == captureConfig.runs()
                    && runs.stream().allMatch(run -> "passed".equals(run.status()));
            BenchmarkCaptureReport report = new BenchmarkCaptureReport(
                    benchmarkId, passed ? "passed" : "failed", captureStarted, Instant.now(),
                    captureConfig.runs(), runs);
            BenchmarkEvidenceWriter.write(reportFile, report, finalEnvironment);
            System.out.println("Benchmark status: " + report.status());
            System.out.println("Evidence report: " + reportFile.toAbsolutePath());
            return Future.succeededFuture(passed ? 0 : 1);
        } catch (Throwable failure) {
            return Future.failedFuture(failure);
        }
    }

    private static <T> Future<CapturedExecution<T>> captureOutput(
            Supplier<Future<T>> operation) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream logBytes = new ByteArrayOutputStream();
        PrintStream log = new PrintStream(logBytes, true, StandardCharsets.UTF_8);
        PrintStream out = new PrintStream(
                new TeeOutputStream(originalOut, log), true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(
                new TeeOutputStream(originalErr, log), true, StandardCharsets.UTF_8);
        System.setOut(out);
        System.setErr(err);
        Future<T> execution;
        try {
            execution = operation.get();
        } catch (Throwable failure) {
            execution = Future.failedFuture(failure);
        }
        return execution.transform(result -> {
            if (result.failed()) {
                result.cause().printStackTrace(System.err);
            }
            System.setOut(originalOut);
            System.setErr(originalErr);
            out.close();
            err.close();
            log.close();
            return Future.succeededFuture(new CapturedExecution<>(
                    result.result(), result.cause(), logBytes.toString(StandardCharsets.UTF_8)));
        });
    }

    private static String shortCommit(BenchmarkEnvironment environment) {
        Object repository = environment.values().get("repository");
        if (repository instanceof java.util.Map<?, ?> map && map.get("commit") instanceof String commit
                && commit.matches("[0-9a-fA-F]{12,}")) {
            return commit.substring(0, 12).toLowerCase(java.util.Locale.ROOT);
        }
        return "no-commit";
    }

    private record CapturedExecution<T>(T result, Throwable failure, String log) {
    }

    private static final class TeeOutputStream extends OutputStream {
        private final OutputStream first;
        private final OutputStream second;

        private TeeOutputStream(OutputStream first, OutputStream second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public synchronized void write(int value) throws IOException {
            first.write(value);
            second.write(value);
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int length) throws IOException {
            first.write(bytes, offset, length);
            second.write(bytes, offset, length);
        }

        @Override
        public synchronized void flush() throws IOException {
            first.flush();
            second.flush();
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }
}
