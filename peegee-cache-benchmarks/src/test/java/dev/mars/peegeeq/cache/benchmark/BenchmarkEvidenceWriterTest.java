package dev.mars.peegeeq.cache.benchmark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkEvidenceWriterTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void writesOneSelfContainedStructuredHtmlEvidenceFile() throws Exception {
        BenchmarkConfig config = BenchmarkConfig.fromSystemProperties();
        List<BenchmarkScenarioResult> scenarios = BenchmarkRunResult.REQUIRED_SCENARIOS.stream()
                .map(name -> new BenchmarkScenarioResult(name, 100, 200.25, 1.0, 2.0, 3.0))
                .toList();
        BenchmarkRunResult result = new BenchmarkRunResult(config, scenarios,
                new BenchmarkTelemetryResult(4.5, 6.5), Duration.ofMillis(7), Duration.ofMillis(8));
        CapturedBenchmarkRun run = CapturedBenchmarkRun.passed(1,
                Instant.parse("2026-08-16T10:00:00Z"), Instant.parse("2026-08-16T10:01:00Z"),
                result, "benchmark output <must be escaped>");
        BenchmarkCaptureReport report = new BenchmarkCaptureReport(
                "capture-id", "passed", Instant.parse("2026-08-16T10:00:00Z"),
                Instant.parse("2026-08-16T10:01:00Z"), 1, List.of(run));
        BenchmarkEnvironment environment = new BenchmarkEnvironment(Map.of(
                "host", Map.of("os", "Test OS", "processors", 8),
                "repository", Map.of("commit", "abc123", "workingTreeClean", true)));

        Path reportFile = temporaryDirectory.resolve("capture-id.html");
        BenchmarkEvidenceWriter.write(reportFile, report, environment);

        try (var files = Files.list(temporaryDirectory)) {
            assertTrue(files.toList().equals(List.of(reportFile)));
        }
        String html = Files.readString(reportFile);
        assertTrue(html.contains("<!doctype html>"));
        assertTrue(html.contains("<h1>peegee-cache benchmark report</h1>"));
        assertTrue(html.contains("<span class=\"header-label\">Generated</span>"));
        assertTrue(html.contains("<time datetime=\"2026-08-16T10:01:00Z\">16 Aug 2026, 10:01:00 UTC</time>"));
        assertTrue(html.contains("<span class=\"header-label\">Report ID</span>"));
        assertTrue(html.contains("<code>capture-id</code>"));
        assertFalse(html.contains("<h1>capture-id</h1>"));
        assertTrue(html.contains("<span class=\"label\">Started UTC</span><time class=\"value\" datetime=\"2026-08-16T10:00:00Z\">16 Aug 2026, 10:00:00 UTC</time>"));
        assertTrue(html.contains("<time datetime=\"2026-08-16T10:00:00Z\">16 Aug 2026, 10:00:00 UTC</time> · 60.000 seconds"));
        assertFalse(html.contains("<span class=\"value\">2026-08-16T10:00:00Z</span>"));
        assertFalse(html.contains("<span class=\"label\">2026-08-16T10:00:00Z"));
        assertTrue(html.contains("Benchmark overview"));
        assertTrue(html.contains("Aggregate results"));
        assertTrue(html.contains("Run 1"));
        assertTrue(html.contains("mixed-set-get-micrometer"));
        assertTrue(html.contains("Test OS"));
        assertTrue(html.contains("benchmark output &lt;must be escaped&gt;"));
        assertFalse(html.contains("benchmark output <must be escaped>"));
    }
}
