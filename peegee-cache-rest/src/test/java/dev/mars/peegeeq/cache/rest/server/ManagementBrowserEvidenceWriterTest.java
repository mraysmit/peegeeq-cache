package dev.mars.peegeeq.cache.rest.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagementBrowserEvidenceWriterTest {

    @Test
    void runtimeCanariesAreRetainedForTheFinalReport() {
        ManagementBrowserEvidenceListener.clearSensitiveCanaries();
        ManagementBrowserEvidenceListener.registerSensitiveCanary("runtime-bootstrap-secret");

        assertEquals(Set.of("runtime-bootstrap-secret"),
                ManagementBrowserEvidenceListener.sensitiveCanaries());
    }

    @Test
    void aNewTestPlanRemovesStaleEvidenceBeforeExecution() throws Exception {
        Path report = temporaryDirectory.resolve("stale-evidence.html");
        Files.writeString(report, "stale");
        Properties properties = new Properties();
        properties.setProperty("peegeeq.playwright.report", report.toString());

        new ManagementBrowserEvidenceListener(
                ManagementBrowserRunConfig.fromProperties(properties))
                .testPlanExecutionStarted(null);

        assertFalse(Files.exists(report));
    }

    @Test
    void completeRunScenarioCountGateRejectsMissingEvidenceRows() {
        assertDoesNotThrow(() -> ManagementBrowserEvidenceListener.assertExpectedScenarioCount(549, 549));
        assertThrows(IllegalStateException.class,
                () -> ManagementBrowserEvidenceListener.assertExpectedScenarioCount(549, 548));
    }

    @TempDir
    Path temporaryDirectory;

    @Test
    void publishesDocumentationGalleryBeforeAcceptingTheReport() throws Exception {
        var report = passingReport(List.of(
                new ManagementBrowserEvidenceReport.Screenshot("viewport", png("PW-ENTRY-001-1-viewport.png", 1440, 900)),
                new ManagementBrowserEvidenceReport.Screenshot("element", png("PW-ENTRY-001-1-element.png", 500, 300))));
        Path destination = temporaryDirectory.resolve("automatic-evidence.html");
        Path gallery = temporaryDirectory.resolve("docs/screenshots");
        ManagementBrowserEvidenceWriter.write(destination, report, gallery);
        assertTrue(Files.exists(gallery.resolve("entry-entry-lifecycle-viewport.png")));
        assertTrue(Files.exists(gallery.resolve("index.html")));
        assertTrue(Files.exists(destination));
        Path blocked = temporaryDirectory.resolve("blocked-gallery");
        Files.writeString(blocked, "existing file");
        Path rejected = temporaryDirectory.resolve("rejected-evidence.html");
        assertThrows(java.io.IOException.class,
                () -> ManagementBrowserEvidenceWriter.write(rejected, report, blocked));
        assertFalse(Files.exists(rejected));
    }

    @Test
    void passingScenarioWithoutScreenshotsCannotProduceAcceptanceEvidence() {
        var original = report("", Set.of());
        var row = original.scenarios().getFirst();
        var passing = new ManagementBrowserEvidenceReport.ScenarioResult(
                row.id(), row.name(), row.area(), row.risk(), "PASSED", 1,
                row.requirement(), row.observedOperations(), row.evidence(), "");
        var report = new ManagementBrowserEvidenceReport(original.reportId(), original.startedAtUtc(),
                original.completedAtUtc(), original.environment(), List.of(passing), Set.of());
        Path destination = temporaryDirectory.resolve("missing-screenshots.html");
        assertThrows(IllegalStateException.class,
                () -> ManagementBrowserEvidenceWriter.write(destination, report));
        assertFalse(Files.exists(destination));
    }

    @Test
    void embedsBothImagesInTheScenarioRowAndRemainsPortable() throws Exception {
        Path viewport = png("PW-ENTRY-001-1-viewport.png", 1440, 900);
        Path element = png("PW-ENTRY-001-1-element.png", 500, 300);
        var report = passingReport(List.of(
                new ManagementBrowserEvidenceReport.Screenshot("viewport", viewport),
                new ManagementBrowserEvidenceReport.Screenshot("element", element)));
        Path destination = temporaryDirectory.resolve("screenshots.html");
        ManagementBrowserEvidenceWriter.write(destination, report);
        String html = Files.readString(destination);
        assertEquals(2, html.split("data:image/png;base64,", -1).length - 1);
        assertTrue(html.contains("View screenshots (2)"));
        assertTrue(html.contains("PW-ENTRY-001-1-viewport.png"));
        assertTrue(html.contains("PW-ENTRY-001-1-element.png"));
        assertTrue(html.contains("Scenarios with screenshots"));
        assertFalse(html.contains(temporaryDirectory.toString()));
    }

    @Test
    void rejectsMissingCorruptWrongSizeAndMismatchedScenarioImages() throws Exception {
        Path viewport = png("PW-ENTRY-001-1-viewport.png", 1440, 900);
        Path element = png("PW-ENTRY-001-1-element.png", 500, 300);
        Path wrongSize = png("PW-ENTRY-001-2-viewport.png", 1280, 720);
        Path otherScenario = png("PW-ENTRY-002-1-viewport.png", 1440, 900);
        Path corrupt = temporaryDirectory.resolve("PW-ENTRY-001-3-viewport.png");
        Files.writeString(corrupt, "not a PNG");
        var focused = new ManagementBrowserEvidenceReport.Screenshot("element", element);
        Path destination = temporaryDirectory.resolve("invalid.html");
        assertThrows(IllegalStateException.class, () -> ManagementBrowserEvidenceWriter.write(destination,
                passingReport(List.of(new ManagementBrowserEvidenceReport.Screenshot("viewport", viewport)))));
        for (Path invalid : List.of(wrongSize, otherScenario, corrupt)) {
            assertThrows(IllegalStateException.class, () -> ManagementBrowserEvidenceWriter.write(destination,
                    passingReport(List.of(new ManagementBrowserEvidenceReport.Screenshot("viewport", invalid), focused))));
        }
        assertFalse(Files.exists(destination));
    }

    private Path png(String name, int width, int height) throws Exception {
        Path path = temporaryDirectory.resolve(name);
        assertTrue(ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", path.toFile()));
        return path;
    }

    private static ManagementBrowserEvidenceReport passingReport(
            List<ManagementBrowserEvidenceReport.Screenshot> screenshots) {
        var original = report("", Set.of());
        var row = original.scenarios().getFirst();
        return new ManagementBrowserEvidenceReport(original.reportId(), original.startedAtUtc(),
                original.completedAtUtc(), original.environment(), List.of(
                new ManagementBrowserEvidenceReport.ScenarioResult(row.id(), row.name(), row.area(), row.risk(),
                        "PASSED", 1, row.requirement(), row.observedOperations(), row.evidence(), "", screenshots)), Set.of());
    }

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
