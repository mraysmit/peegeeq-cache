package dev.mars.peegeeq.cache.rest.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ManagementBrowserDiagnosticsTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void rejectsEveryUnexpectedFailedBrowserResponse() {
        ManagementConsolePostgresFixture.Diagnostics diagnostics =
                new ManagementConsolePostgresFixture.Diagnostics();
        diagnostics.recordResponse(500, "/api/v1/setups/browser/overview");

        assertThrows(AssertionError.class, diagnostics::assertNoUnexpectedFailedResponses);
    }

    @Test
    void acceptsOnlyTheExactExpectedFailureResponse() {
        ManagementConsolePostgresFixture.Diagnostics diagnostics =
                new ManagementConsolePostgresFixture.Diagnostics();
        diagnostics.expectFailedResponse(409,
                "/api/v1/setups/{setupId}/namespaces/{encodedNamespace}/counters/{encodedKey}");
        diagnostics.recordResponse(409, "/api/v1/setups/browser/namespaces/orders/counters/count");

        assertDoesNotThrow(diagnostics::assertNoUnexpectedFailedResponses);
    }

    @Test
    void rejectsAnExpectedFailureWhenItsStatusOrPathIsWrong() {
        ManagementConsolePostgresFixture.Diagnostics wrongStatus =
                new ManagementConsolePostgresFixture.Diagnostics();
        wrongStatus.expectFailedResponse(409,
                "/api/v1/setups/{setupId}/namespaces/{encodedNamespace}/counters/{encodedKey}");
        wrongStatus.recordResponse(422, "/api/v1/setups/browser/namespaces/orders/counters/count");

        ManagementConsolePostgresFixture.Diagnostics wrongPath =
                new ManagementConsolePostgresFixture.Diagnostics();
        wrongPath.expectFailedResponse(409,
                "/api/v1/setups/{setupId}/namespaces/{encodedNamespace}/counters/{encodedKey}");
        wrongPath.recordResponse(409, "/api/v1/setups/browser/namespaces/orders/locks/other");

        assertThrows(AssertionError.class, wrongStatus::assertNoUnexpectedFailedResponses);
        assertThrows(AssertionError.class, wrongPath::assertNoUnexpectedFailedResponses);
    }

    @Test
    void requiresDurableAuditRecordsForEveryDeclaredAuditedOperation() throws Exception {
        Path audit = temporaryDirectory.resolve("audit.jsonl");
        Files.writeString(audit, "{\"action\":\"SET_ENTRY\",\"outcome\":\"SUCCEEDED\"}\n");

        assertDoesNotThrow(() -> ManagementConsolePostgresFixture.assertDurableAuditObserved(
                audit, List.of("setEntry")));
        assertThrows(AssertionError.class,
                () -> ManagementConsolePostgresFixture.assertDurableAuditObserved(
                        audit, List.of("deleteEntry")));
    }

    @Test
    void rejectsUndeclaredAuditedBrowserOperationsButAllowsFixtureSetup() {
        assertDoesNotThrow(() -> ManagementConsolePostgresFixture.assertNoUndeclaredAuditedOperations(
                java.util.Set.of("registerSetup", "setEntry"), List.of("setEntry")));
        assertThrows(AssertionError.class,
                () -> ManagementConsolePostgresFixture.assertNoUndeclaredAuditedOperations(
                        java.util.Set.of("registerSetup", "deleteEntry"), List.of("setEntry")));
    }

    @Test
    void databaseOracleRejectsAValueThatDoesNotMatchPostgresqlTruth() {
        assertDoesNotThrow(() -> ManagementConsolePostgresFixture.assertDatabaseValue(
                "committed", "committed", "entry value"));
        assertThrows(AssertionError.class, () -> ManagementConsolePostgresFixture.assertDatabaseValue(
                "committed", "stale", "entry value"));
    }

    @Test
    void cleanupOracleRejectsAnyReportedResourceLeak() {
        assertDoesNotThrow(() -> ManagementConsolePostgresFixture.assertNoLeakedResources(0.0));
        assertThrows(AssertionError.class,
                () -> ManagementConsolePostgresFixture.assertNoLeakedResources(1.0));
    }
}
