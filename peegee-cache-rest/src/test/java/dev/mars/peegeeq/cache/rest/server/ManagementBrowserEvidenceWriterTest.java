package dev.mars.peegeeq.cache.rest.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagementBrowserEvidenceWriterTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void writesOneSelfContainedStructuredHtmlReportWithReadableUtcDates() throws Exception {
        ManagementBrowserEvidenceReport report = report(
                "A visible <failure> is escaped",
                Set.of("bootstrap-canary-not-present"));
        Path reportFile = temporaryDirectory.resolve("playwright-evidence.html");

        ManagementBrowserEvidenceWriter.write(reportFile, report);

        try (var files = Files.list(temporaryDirectory)) {
            assertTrue(files.toList().equals(List.of(reportFile)));
        }
        String html = Files.readString(reportFile);
        assertTrue(html.contains("<!doctype html>"));
        assertTrue(html.contains("<h1>PeeGeeQ Cache Playwright evidence</h1>"));
        assertTrue(html.contains("<time datetime=\"2026-08-30T07:02:03Z\">30 Aug 2026, 07:02:03 UTC</time>"));
        assertTrue(html.contains("PW-ENTRY-001"));
        assertTrue(html.contains("setEntry"));
        assertTrue(html.contains("DATABASE"));
        assertTrue(html.contains("Java 26.0.2"));
        assertTrue(html.contains("PostgreSQL 18.3"));
        assertTrue(html.contains("A visible &lt;failure&gt; is escaped"));
        assertFalse(html.contains("A visible <failure> is escaped"));
        assertFalse(html.contains("bootstrap-canary-not-present"));
        assertFalse(html.contains("<script src="));
        assertFalse(html.contains("<link rel=\"stylesheet\""));
    }

    @Test
    void refusesToWriteAReportContainingRegisteredSensitiveCanaries() {
        ManagementBrowserEvidenceReport report = report(
                "Failure accidentally contains bootstrap-secret-123",
                Set.of("bootstrap-secret-123"));
        Path reportFile = temporaryDirectory.resolve("unsafe.html");

        assertThrows(IllegalStateException.class,
                () -> ManagementBrowserEvidenceWriter.write(reportFile, report));
        assertFalse(Files.exists(reportFile));
    }

    private static ManagementBrowserEvidenceReport report(String failure, Set<String> sensitiveCanaries) {
        return new ManagementBrowserEvidenceReport(
                "playwright-20260830-070000",
                Instant.parse("2026-08-30T07:00:00Z"),
                Instant.parse("2026-08-30T07:02:03Z"),
                new ManagementBrowserEvidenceReport.Environment(
                        "Java 26.0.2",
                        "Windows 11",
                        "Test CPU",
                        "32 GiB",
                        "Chromium 140",
                        "PostgreSQL 18.3",
                        "abc123"),
                List.of(new ManagementBrowserEvidenceReport.ScenarioResult(
                        "PW-ENTRY-001",
                        "Entry lifecycle",
                        ManagementBrowserArea.ENTRY,
                        ManagementBrowserRisk.CRITICAL,
                        "FAILED",
                        1234,
                        "Management UI design: entry lifecycle",
                        List.of("getEntry", "setEntry"),
                        Set.of(
                                ManagementBrowserEvidence.VISIBLE_RESULT,
                                ManagementBrowserEvidence.HTTP_OPERATION,
                                ManagementBrowserEvidence.DATABASE,
                                ManagementBrowserEvidence.RESOURCE_CLEANUP),
                        failure)),
                sensitiveCanaries);
    }
}
