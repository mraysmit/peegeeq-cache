package dev.mars.peegeeq.cache.benchmark;

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

/** Java-first entry point for repeated benchmarks and reproducible evidence capture. */
public final class BenchmarkCaptureMain {

    private static final DateTimeFormatter ID_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC);

    private BenchmarkCaptureMain() {
    }

    public static void main(String[] args) {
        int exitCode;
        try {
            exitCode = run(BenchmarkCaptureConfig.fromSystemProperties(), BenchmarkConfig.fromSystemProperties());
        } catch (Throwable failure) {
            failure.printStackTrace(System.err);
            exitCode = 1;
        }
        System.exit(exitCode);
    }

    static int run(BenchmarkCaptureConfig captureConfig, BenchmarkConfig benchmarkConfig) throws IOException {
        Instant captureStarted = Instant.now();
        BenchmarkEnvironment environment = BenchmarkEnvironment.capture(
                captureConfig.repositoryRoot(), captureConfig.topology(),
                captureConfig.postgresImage(), benchmarkConfig);
        String benchmarkId = ID_TIME.format(captureStarted) + "-" + shortCommit(environment);
        Files.createDirectories(captureConfig.resolvedOutputRoot());
        Path reportFile = captureConfig.resolvedOutputRoot().resolve(benchmarkId + ".html");
        System.out.println("Benchmark evidence: " + reportFile.toAbsolutePath());

        if (captureConfig.requireCleanGit() && !environment.workingTreeClean()) {
            BenchmarkCaptureReport rejected = new BenchmarkCaptureReport(
                    benchmarkId, "rejected-dirty-working-tree", captureStarted, Instant.now(),
                    captureConfig.runs(), List.of());
            BenchmarkEvidenceWriter.write(reportFile, rejected, environment);
            System.err.println("Working tree is dirty; benchmark capture was rejected.");
            return 2;
        }

        List<CapturedBenchmarkRun> runs = new ArrayList<>();
        for (int runNumber = 1; runNumber <= captureConfig.runs(); runNumber++) {
            Instant started = Instant.now();
            CapturedExecution<BenchmarkRunResult> execution = captureOutput(
                    () -> CacheBenchmarkMain.run(benchmarkConfig));
            CapturedBenchmarkRun captured = execution.failure() == null
                    ? CapturedBenchmarkRun.passed(
                            runNumber, started, Instant.now(), execution.result(), execution.log())
                    : CapturedBenchmarkRun.failed(
                            runNumber, started, Instant.now(), execution.failure(), execution.log());
            runs.add(captured);
            String interimStatus = "passed".equals(captured.status()) ? "running" : "failed";
            BenchmarkEvidenceWriter.write(reportFile, new BenchmarkCaptureReport(
                    benchmarkId, interimStatus, captureStarted, Instant.now(),
                    captureConfig.runs(), runs), environment);
            if (!"passed".equals(captured.status()) && captureConfig.stopOnFailure()) {
                break;
            }
        }

        environment = BenchmarkEnvironment.capture(
                captureConfig.repositoryRoot(), captureConfig.topology(),
                captureConfig.postgresImage(), benchmarkConfig);
        boolean passed = runs.size() == captureConfig.runs()
                && runs.stream().allMatch(run -> "passed".equals(run.status()));
        BenchmarkCaptureReport report = new BenchmarkCaptureReport(
                benchmarkId, passed ? "passed" : "failed", captureStarted, Instant.now(),
                captureConfig.runs(), runs);
        BenchmarkEvidenceWriter.write(reportFile, report, environment);
        System.out.println("Benchmark status: " + report.status());
        System.out.println("Evidence report: " + reportFile.toAbsolutePath());
        return passed ? 0 : 1;
    }

    private static <T> CapturedExecution<T> captureOutput(ThrowingSupplier<T> operation) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream logBytes = new ByteArrayOutputStream();
        T result = null;
        Throwable failure = null;
        try (PrintStream log = new PrintStream(logBytes, true, StandardCharsets.UTF_8);
             PrintStream out = new PrintStream(new TeeOutputStream(originalOut, log), true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(new TeeOutputStream(originalErr, log), true, StandardCharsets.UTF_8)) {
            System.setOut(out);
            System.setErr(err);
            try {
                result = operation.get();
            } catch (Throwable caught) {
                failure = caught;
                caught.printStackTrace(System.err);
            }
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return new CapturedExecution<>(result, failure, logBytes.toString(StandardCharsets.UTF_8));
    }

    private static String shortCommit(BenchmarkEnvironment environment) {
        Object repository = environment.values().get("repository");
        if (repository instanceof java.util.Map<?, ?> map && map.get("commit") instanceof String commit
                && commit.matches("[0-9a-fA-F]{12,}")) {
            return commit.substring(0, 12).toLowerCase(java.util.Locale.ROOT);
        }
        return "no-commit";
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
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
