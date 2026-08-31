package dev.mars.peegeeq.cache.rest.server;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ManagementBrowserAccountabilityTest {

    private static final Set<String> KNOWN_OPERATIONS = Set.of("getEntry", "setEntry", "revealEntryValue");
    private static final Set<String> MUTATIONS = Set.of("setEntry");
    private static final Set<String> AUDITED = Set.of("setEntry", "revealEntryValue");
    private static final Set<String> SENSITIVE = Set.of("revealEntryValue");

    @Test
    void acceptsCompleteScenarioEvidence() throws NoSuchMethodException {
        assertEquals(Set.of(), violations("completeScenario"));
    }

    @Test
    void rejectsIncompleteMetadataAndUnknownOperations() throws NoSuchMethodException {
        assertEquals(Set.of(
                "invalid scenario ID",
                "missing source requirement",
                "missing browser action",
                "missing expected result",
                "missing cleanup obligation",
                "missing visible-result evidence",
                "missing cleanup evidence",
                "unknown operations [inventedOperation]",
                "operations declared without operation evidence"),
                violations("incompleteScenario"));
    }

    @Test
    void rejectsMutationAndSensitiveScenariosWithoutTheirRequiredOracles() throws NoSuchMethodException {
        assertEquals(Set.of(
                "database mutation lacks database evidence",
                "audited operation lacks durable-audit evidence",
                "sensitive operation lacks leakage evidence"),
                violations("missingStateOracles"));
    }

    @Test
    void rejectsDatabaseMutationEvidenceThatOmitsTheDurableAuditOracle() throws NoSuchMethodException {
        assertEquals(Set.of("audited operation lacks durable-audit evidence"),
                violations("databaseWithoutAudit"));
    }

    @Test
    void rejectsDurableAuditEvidenceThatOmitsTheDatabaseMutationOracle() throws NoSuchMethodException {
        assertEquals(Set.of("database mutation lacks database evidence"),
                violations("auditWithoutDatabase"));
    }

    private static Set<String> violations(String methodName) throws NoSuchMethodException {
        Method method = CanaryScenarios.class.getDeclaredMethod(methodName);
        return Set.copyOf(ManagementBrowserAccountability.violations(
                method, KNOWN_OPERATIONS, MUTATIONS, AUDITED, SENSITIVE));
    }

    private static final class CanaryScenarios {

        @ManagementBrowserScenario(
                id = "PW-ENTRY-999",
                requirement = "Management UI design: complete canary",
                area = ManagementBrowserArea.ENTRY,
                risk = ManagementBrowserRisk.HIGH,
                action = "Set and reveal one entry",
                expectedResult = "The committed value is visible only after reveal",
                cleanup = "Delete the entry and close the isolated context",
                operations = {"setEntry", "revealEntryValue"},
                evidence = {
                        ManagementBrowserEvidence.VISIBLE_RESULT,
                        ManagementBrowserEvidence.HTTP_OPERATION,
                        ManagementBrowserEvidence.DATABASE,
                        ManagementBrowserEvidence.DURABLE_AUDIT,
                        ManagementBrowserEvidence.SENSITIVE_STATE,
                        ManagementBrowserEvidence.RESOURCE_CLEANUP
                })
        void completeScenario() {
        }

        @ManagementBrowserScenario(
                id = "bad-id",
                requirement = "",
                area = ManagementBrowserArea.ENTRY,
                risk = ManagementBrowserRisk.MEDIUM,
                action = "",
                expectedResult = "",
                cleanup = "",
                operations = {"inventedOperation"},
                evidence = {})
        void incompleteScenario() {
        }

        @ManagementBrowserScenario(
                id = "PW-ENTRY-998",
                requirement = "Management UI design: missing-oracle canary",
                area = ManagementBrowserArea.ENTRY,
                risk = ManagementBrowserRisk.CRITICAL,
                action = "Set and reveal one entry",
                expectedResult = "The canary validator rejects incomplete evidence",
                cleanup = "Close the isolated context",
                operations = {"setEntry", "revealEntryValue"},
                evidence = {
                        ManagementBrowserEvidence.VISIBLE_RESULT,
                        ManagementBrowserEvidence.HTTP_OPERATION,
                        ManagementBrowserEvidence.RESOURCE_CLEANUP
                })
        void missingStateOracles() {
        }

        @ManagementBrowserScenario(
                id = "PW-ENTRY-997",
                requirement = "Management UI design: database-only oracle canary",
                area = ManagementBrowserArea.ENTRY,
                risk = ManagementBrowserRisk.CRITICAL,
                action = "Set one entry with only a database oracle",
                expectedResult = "The validator rejects the missing durable audit oracle",
                cleanup = "Delete the entry and close the isolated context",
                operations = {"setEntry"},
                evidence = {
                        ManagementBrowserEvidence.VISIBLE_RESULT,
                        ManagementBrowserEvidence.HTTP_OPERATION,
                        ManagementBrowserEvidence.DATABASE,
                        ManagementBrowserEvidence.RESOURCE_CLEANUP
                })
        void databaseWithoutAudit() {
        }

        @ManagementBrowserScenario(
                id = "PW-ENTRY-996",
                requirement = "Management UI design: audit-only oracle canary",
                area = ManagementBrowserArea.ENTRY,
                risk = ManagementBrowserRisk.CRITICAL,
                action = "Set one entry with only a durable audit oracle",
                expectedResult = "The validator rejects the missing PostgreSQL oracle",
                cleanup = "Delete the entry and close the isolated context",
                operations = {"setEntry"},
                evidence = {
                        ManagementBrowserEvidence.VISIBLE_RESULT,
                        ManagementBrowserEvidence.HTTP_OPERATION,
                        ManagementBrowserEvidence.DURABLE_AUDIT,
                        ManagementBrowserEvidence.RESOURCE_CLEANUP
                })
        void auditWithoutDatabase() {
        }
    }
}
