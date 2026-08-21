package dev.mars.peegeeq.cache.api.management;

import org.junit.jupiter.api.Test;

import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.ValueType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagementCoreModelsTest {

    @Test
    void adminPagesAreImmutableAndCursorStateIsConsistent() {
        List<String> source = new ArrayList<>(List.of("one"));
        AdminPage<String> page = new AdminPage<>(source, "next", true);
        source.add("two");

        assertEquals(List.of("one"), page.items());
        assertThrows(UnsupportedOperationException.class, () -> page.items().add("three"));
        assertThrows(IllegalArgumentException.class, () -> new AdminPage<>(List.of(), null, true));
        assertThrows(IllegalArgumentException.class, () -> new AdminPage<>(List.of(), "next", false));
    }

    @Test
    void queriesEnforceBoundsAndUseBoundedSorts() {
        NamespaceQuery query = new NamespaceQuery("customer%", NamespaceQuery.Status.READY,
                NamespaceQuery.Sort.ENTRY_COUNT_DESC, null, 200);

        assertEquals("customer%", query.prefix());
        assertEquals(NamespaceQuery.Sort.ENTRY_COUNT_DESC, query.sort());
        assertThrows(IllegalArgumentException.class,
                () -> new NamespaceQuery(null, null, NamespaceQuery.Sort.NAMESPACE_ASC, null, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new NamespaceQuery(null, null, NamespaceQuery.Sort.NAMESPACE_ASC, null, 201));
    }

    @Test
    void actionContextNormalizesRolesAndRejectsUnboundedIdentityData() {
        ManagementActionContext context = new ManagementActionContext(
                " operator-1 ", Set.of("VIEWER", "operator"), "request-1", "127.0.0.1");

        assertEquals("operator-1", context.actor());
        assertEquals(Set.of("viewer", "operator"), context.roles());
        assertTrue(context.hasRole("OPERATOR"));
        assertFalse(context.hasRole("admin"));
        assertThrows(IllegalArgumentException.class,
                () -> new ManagementActionContext("a".repeat(129), Set.of("viewer"), "c", "s"));
        assertThrows(IllegalArgumentException.class,
                () -> new ManagementActionContext("actor", Set.of(), "c", "s"));
    }

    @Test
    void versionedMutationResultsAllowOnlyOutcomeConsistentState() {
        VersionedMutationResult<String> applied = VersionedMutationResult.applied(7, "current");
        assertEquals(ManagementMutationOutcome.APPLIED, applied.outcome());
        assertEquals(7, applied.resultingVersion());
        assertEquals("current", applied.representation());

        assertEquals(VersionedMutationResult.notFound(),
                new VersionedMutationResult<>(ManagementMutationOutcome.NOT_FOUND, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new VersionedMutationResult<>(ManagementMutationOutcome.NOT_FOUND, 7L, null));
        assertThrows(IllegalArgumentException.class,
                () -> new VersionedMutationResult<>(ManagementMutationOutcome.APPLIED, null, "current"));
    }

    @Test
    void setResultsKeepCreatedAppliedAndVersionStateConsistent() {
        ManagementEntryMetadata metadata = metadata(1);
        ManagementSetResult created = ManagementSetResult.created(1, metadata);
        assertTrue(created.created());
        assertEquals(ManagementMutationOutcome.APPLIED, created.outcome());

        assertThrows(IllegalArgumentException.class,
                () -> new ManagementSetResult(ManagementMutationOutcome.CONDITION_NOT_MET,
                        true, null, null));
    }

    private static ManagementEntryMetadata metadata(long version) {
        Instant now = Instant.parse("2026-08-20T06:00:00Z");
        return new ManagementEntryMetadata(new CacheKey("ns", "key"), ValueType.STRING,
                1, version, now, now, ManagementTtl.persistent());
    }
}
