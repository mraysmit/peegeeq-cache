package dev.mars.peegeeq.cache.rest.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagementConsoleProductJourneysIT {

    @RegisterExtension
    static final ManagementBrowserPostgresWorker POSTGRES = new ManagementBrowserPostgresWorker();

    @TempDir
    Path temporaryDirectory;

    @ManagementBrowserScenario(
            id = "PW-SETUP-001",
            requirement = "UI design: complete setup test, register, inspect, detach, reconnect, and forget lifecycle",
            area = ManagementBrowserArea.SETUP,
            risk = ManagementBrowserRisk.CRITICAL,
            action = "Drive the complete setup lifecycle through the packaged console against PostgreSQL",
            expectedResult = "Each setup state and health transition is visible and credentials remain confined",
            cleanup = "Forget the setup and close browser, server, pool, schema, and PostgreSQL resources",
            operations = {
                    "testUnregisteredSetup", "registerSetup", "getSetup", "connectSetup",
                    "testRegisteredSetup", "detachSetup", "forgetSetup", "getSetupHealth"
            },
            evidence = {
                    ManagementBrowserEvidence.VISIBLE_RESULT,
                    ManagementBrowserEvidence.HTTP_OPERATION,
                    ManagementBrowserEvidence.DATABASE,
                    ManagementBrowserEvidence.SENSITIVE_STATE,
                    ManagementBrowserEvidence.RESOURCE_CLEANUP
            })
    @ManagementBrowserJourney(
            value = "setup-lifecycle",
            operations = {
                    "testUnregisteredSetup", "registerSetup", "getSetup", "connectSetup",
                    "testRegisteredSetup", "detachSetup", "forgetSetup", "getSetupHealth"
            })
    void setupTestRegisterInspectDetachReconnectAndForgetUsesTheRealDatabase() throws Exception {
        ManagementConsolePostgresFixture.run(temporaryDirectory, POSTGRES.postgres(), false, context -> {
            ManagementConsolePostgresFixture.authenticate(context);
            ManagementConsolePostgresFixture.registerSetup(context);
            Page page = context.page();
            Locator row = setupRow(page);

            row.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Test").setExact(true)).click();
            assertThat(page.getByRole(AriaRole.STATUS)).containsText("responded in");

            row.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Details")).click();
            Locator details = page.getByRole(AriaRole.DIALOG,
                    new Page.GetByRoleOptions().setName("Setup details"));
            assertThat(details).containsText("Database reachable and schema ready");
            details.getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Close details")).click();

            confirmRowAction(page, "Detach");
            assertThat(setupRow(page)).containsText("Detached");
            assertThat(page.getByTitle("Active setup scope")).hasText("No setup selected");

            confirmRowAction(page, "Connect");
            assertThat(setupRow(page)).containsText("Connected");
            setupRow(page).getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Use setup")).click();
            assertThat(page.getByTitle("Active setup scope"))
                    .hasText("Setup: " + ManagementConsolePostgresFixture.SETUP_ID);

            confirmRowAction(page, "Forget");
            assertThat(page.getByRole(AriaRole.HEADING,
                    new Page.GetByRoleOptions().setName("No setups registered"))).isVisible();
            assertEquals(0, ((Number) page.evaluate("sessionStorage.length")).intValue());

            page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Register the first setup")).click();
            Locator forbidden = page.getByRole(AriaRole.DIALOG,
                    new Page.GetByRoleOptions().setName("Register setup"));
            forbidden.getByLabel("Setup ID").fill("forbidden-target");
            forbidden.getByLabel("Display name").fill("Forbidden target");
            forbidden.getByLabel("Host").fill("public.example.net");
            forbidden.getByLabel("Port").fill(String.valueOf(context.postgres().getMappedPort(5432)));
            forbidden.getByLabel("Database").fill("peegeeq");
            forbidden.getByLabel("Schema").fill("peegee_cache");
            forbidden.getByLabel("Username").fill("peegeeq");
            forbidden.getByLabel("Password").fill(ManagementConsolePostgresFixture.DATABASE_PASSWORD);
            forbidden.getByLabel("Trust profile").fill("test-ca");
            forbidden.getByLabel("Pool size").fill("1");
            forbidden.getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Test connection")).click();
            assertThat(forbidden.getByRole(AriaRole.ALERT)).containsText("TARGET_FORBIDDEN");
            String nonControlDom = String.valueOf(page.evaluate("""
                    () => {
                      const clone = document.documentElement.cloneNode(true);
                      clone.querySelectorAll('input[type="password"]').forEach(input => input.remove());
                      return clone.outerHTML;
                    }
                    """));
            assertFalse(nonControlDom.contains(ManagementConsolePostgresFixture.DATABASE_PASSWORD));
            assertFalse(page.url().contains(ManagementConsolePostgresFixture.DATABASE_PASSWORD));
            assertFalse(String.valueOf(page.evaluate(
                    "JSON.stringify([...Object.values(localStorage), ...Object.values(sessionStorage)])"))
                    .contains(ManagementConsolePostgresFixture.DATABASE_PASSWORD));
        });
    }

    @ManagementBrowserScenario(
            id = "PW-SHELL-001",
            requirement = "UI design: setup and namespace scope persistence must be revalidated against capabilities",
            area = ManagementBrowserArea.SHELL,
            risk = ManagementBrowserRisk.HIGH,
            action = "Select a setup and namespace, reload the page, and inspect capability-driven navigation",
            expectedResult = "Valid scope is restored and only server-advertised feature navigation is available",
            cleanup = "Clear the isolated context scope and close all fixture resources",
            operations = {"getSetupCapabilities", "getNamespace"},
            evidence = {
                    ManagementBrowserEvidence.VISIBLE_RESULT,
                    ManagementBrowserEvidence.HTTP_OPERATION,
                    ManagementBrowserEvidence.DATABASE,
                    ManagementBrowserEvidence.RESOURCE_CLEANUP
            })
    @ManagementBrowserJourney(
            value = "scope-and-capabilities",
            operations = {"getSetupCapabilities", "getNamespace"})
    void setupAndNamespaceScopeAreRevalidatedByTheRealCapabilityContract() throws Exception {
        ManagementConsolePostgresFixture.run(temporaryDirectory, POSTGRES.postgres(), true, context -> {
            ManagementConsolePostgresFixture.authenticate(context);
            ManagementConsolePostgresFixture.registerSetup(context);
            Page page = context.page();

            assertThat(page.getByRole(AriaRole.LINK,
                    new Page.GetByRoleOptions().setName("Counters").setExact(true))).isVisible();
            openNamespace(page);
            assertThat(page.getByRole(AriaRole.HEADING,
                    new Page.GetByRoleOptions().setName(ManagementConsolePostgresFixture.NAMESPACE)
                            .setExact(true))).isVisible();
            assertEquals(
                    "{\"setupId\":\"browser-postgres\",\"namespace\":\"logical-orders\"}",
                    page.evaluate("sessionStorage.getItem('peegeeq-cache.scope.v1')"));

            page.reload();
            assertThat(page.getByRole(AriaRole.HEADING,
                    new Page.GetByRoleOptions().setName(ManagementConsolePostgresFixture.NAMESPACE)
                            .setExact(true))).isVisible();
            assertThat(page.getByTitle("Active setup scope"))
                    .hasText("Setup: " + ManagementConsolePostgresFixture.SETUP_ID);
        });
    }

    @ManagementBrowserScenario(
            id = "PW-OVERVIEW-001",
            requirement = "UI design: overview, namespace pagination, export, monitoring, and activity reflect database truth",
            area = ManagementBrowserArea.OVERVIEW,
            risk = ManagementBrowserRisk.HIGH,
            action = "Browse overview metrics, paginate namespaces, export them, and inspect monitoring and activity",
            expectedResult = "Visible totals and exported items match PostgreSQL without exposing cursor internals",
            cleanup = "Remove scenario data and close download, browser, server, and PostgreSQL resources",
            operations = {
                    "getOverview", "listNamespaces", "exportNamespaces",
                    "getDatabaseMonitoring", "getRuntimeMonitoring", "listActivity"
            },
            evidence = {
                    ManagementBrowserEvidence.VISIBLE_RESULT,
                    ManagementBrowserEvidence.HTTP_OPERATION,
                    ManagementBrowserEvidence.DATABASE,
                    ManagementBrowserEvidence.RESOURCE_CLEANUP
            })
    @ManagementBrowserJourney(
            value = "overview-and-namespaces",
            operations = {
                    "getOverview", "listNamespaces", "exportNamespaces",
                    "getDatabaseMonitoring", "getRuntimeMonitoring", "listActivity"
            })
    void overviewNamespacePagingExportAndMonitoringReflectDatabaseTruth() throws Exception {
        ManagementConsolePostgresFixture.run(temporaryDirectory, POSTGRES.postgres(), true, context -> {
            ManagementConsolePostgresFixture.authenticate(context);
            ManagementConsolePostgresFixture.registerSetup(context);
            Page page = context.page();

            page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Overview")).click();
            assertThat(metric(page, "Live cache entries")).containsText("4");
            assertThat(metric(page, "Live counters")).containsText("1");
            assertThat(metric(page, "Active locks")).containsText("1");
            assertThat(page.getByText("Database-wide", new Page.GetByTextOptions().setExact(true)))
                    .isVisible();
            assertThat(page.getByText("Management-server-local",
                    new Page.GetByTextOptions().setExact(true))).isVisible();

            ManagementConsolePostgresFixture.execute(context.postgres(), """
                    INSERT INTO peegee_cache.cache_entries
                        (namespace, cache_key, value_type, value_bytes, numeric_value, version)
                    SELECT 'page-ns-' || lpad(series::text, 2, '0'),
                           'cursor-key', 'STRING', convert_to('cursor-value', 'UTF8'), NULL, 1
                      FROM generate_series(1, 51) AS series
                    """);
            page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Namespaces")).click();
            assertThat(page.getByRole(AriaRole.ROW)
                    .filter(new Locator.FilterOptions().setHasText(ManagementConsolePostgresFixture.NAMESPACE)))
                    .containsText("1");
            assertThat(page.getByText("Page 1", new Page.GetByTextOptions().setExact(true))).isVisible();
            page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Next page")).click();
            assertThat(page.getByText("Page 2", new Page.GetByTextOptions().setExact(true))).isVisible();
            assertThat(page.getByRole(AriaRole.LINK,
                    new Page.GetByRoleOptions().setName("page-ns-51").setExact(true))).isVisible();
            page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Previous page")).click();
            assertThat(page.getByText("Page 1", new Page.GetByTextOptions().setExact(true))).isVisible();
            assertThat(page.getByRole(AriaRole.LINK,
                    new Page.GetByRoleOptions().setName(ManagementConsolePostgresFixture.NAMESPACE)
                            .setExact(true))).isVisible();
            var download = page.waitForDownload(() -> page.getByRole(
                    AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Export namespaces")).click());
            assertTrue(download.suggestedFilename().endsWith(".json"));
            assertFalse(download.failure() != null, () -> "Namespace export failed: " + download.failure());
            JsonNode exported = new ObjectMapper().readTree(download.path().toFile());
            assertEquals(52, exported.path("items").size());
            assertTrue(exported.path("items").findValuesAsText("namespace")
                    .containsAll(List.of("logical-orders", "page-ns-51")));
            assertFalse(exported.toString().contains("cursor-value"));
        });
    }

    @ManagementBrowserScenario(
            id = "PW-ENTRY-001",
            requirement = "UI design: version-checked entry inspection, reveal, edit, TTL, persistence, touch, and deletion",
            area = ManagementBrowserArea.ENTRY,
            risk = ManagementBrowserRisk.CRITICAL,
            action = "Drive a complete entry lifecycle including masked reveal and version-checked mutations",
            expectedResult = "The UI renders exact values and outcomes while PostgreSQL records each committed transition",
            cleanup = "Hide revealed values, delete scenario entries, and close all fixture resources",
            operations = {
                    "listEntries", "getEntry", "revealEntryValue", "setEntry",
                    "deleteEntry", "expireEntry", "persistEntry", "touchEntry"
            },
            evidence = {
                    ManagementBrowserEvidence.VISIBLE_RESULT,
                    ManagementBrowserEvidence.HTTP_OPERATION,
                    ManagementBrowserEvidence.DATABASE,
                    ManagementBrowserEvidence.SENSITIVE_STATE,
                    ManagementBrowserEvidence.RESOURCE_CLEANUP
            })
    @ManagementBrowserJourney(
            value = "entry-lifecycle",
            operations = {
                    "listEntries", "getEntry", "revealEntryValue", "setEntry",
                    "deleteEntry", "expireEntry", "persistEntry", "touchEntry"
            })
    void entryCreateBrowseRevealEditExpirePersistTouchAndDeleteIsVersionChecked() throws Exception {
        ManagementConsolePostgresFixture.run(temporaryDirectory, POSTGRES.postgres(), true, context -> {
            ManagementConsolePostgresFixture.authenticate(context);
            ManagementConsolePostgresFixture.registerSetup(context);
            Page page = context.page();
            openEntry(page, "customer:1");

            assertThat(page.getByText("Value hidden", new Page.GetByTextOptions().setExact(true)))
                    .isVisible();
            page.getByLabel("Reveal reason (optional)").fill("real browser verification");
            page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Reveal value").setExact(true)).click();
            assertThat(page.getByText("stored-value", new Page.GetByTextOptions().setExact(true)))
                    .isVisible();
            context.browserContext().grantPermissions(
                    List.of("clipboard-read", "clipboard-write"),
                    new BrowserContext.GrantPermissionsOptions().setOrigin(context.origin()));
            page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Copy revealed value")).click();
            assertThat(page.getByText("Copied", new Page.GetByTextOptions().setExact(true))).isVisible();
            assertEquals("stored-value", page.evaluate("navigator.clipboard.readText()"));
            page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Hide value").setExact(true)).click();

            page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Edit entry").setExact(true)).click();
            page.getByLabel("Entry value").fill("updated-value");
            ManagementConsolePostgresFixture.execute(context.postgres(), """
                    UPDATE peegee_cache.cache_entries
                       SET version = version + 1
                     WHERE namespace = 'logical-orders' AND cache_key = 'customer:1'
                    """);
            page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Save entry").setExact(true)).click();
            assertThat(page.getByRole(AriaRole.ALERT)).containsText("VERSION_MISMATCH");
            assertThat(page.getByLabel("Entry value")).hasValue("updated-value");
            page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Save entry").setExact(true)).click();
            assertThat(page.getByRole(AriaRole.STATUS)).containsText("Entry updated");

            page.getByLabel("TTL milliseconds", new Page.GetByLabelOptions().setExact(true))
                    .fill("60000");
            page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Set TTL").setExact(true)).click();
            assertThat(page.getByRole(AriaRole.STATUS)).containsText("TTL updated");
            page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Make persistent").setExact(true)).click();
            assertThat(page.getByRole(AriaRole.STATUS)).containsText("persistent");
            page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Touch entry").setExact(true)).click();
            assertThat(page.getByRole(AriaRole.STATUS)).containsText("authoritative metadata");

            page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Delete entry").setExact(true)).click();
            Locator deletion = page.getByRole(AriaRole.DIALOG,
                    new Page.GetByRoleOptions().setName("Delete customer:1?"));
            deletion.getByLabel("Confirm entry key").fill("customer:1");
            deletion.getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Confirm delete")).click();
            assertThat(page.getByRole(AriaRole.STATUS)).containsText("deleted");

            openEntry(page, "json-record");
            revealCurrentEntry(page);
            assertThat(page.getByText("Valid JSON", new Page.GetByTextOptions().setExact(true)))
                    .isVisible();
            page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Formatted text")).click();
            assertThat(page.locator(".value-content")).containsText("\"safe\": true");
            hideCurrentEntry(page);

            openEntry(page, "long-boundary");
            revealCurrentEntry(page);
            assertThat(page.getByText("9223372036854775807",
                    new Page.GetByTextOptions().setExact(true))).isVisible();
            hideCurrentEntry(page);

            openEntry(page, "binary-record");
            revealCurrentEntry(page);
            assertThat(page.locator(".value-content")).containsText("00 ff 41");
            page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Base64")).click();
            assertThat(page.getByText("AP9B", new Page.GetByTextOptions().setExact(true))).isVisible();
            page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("UTF-8 attempt")).click();
            assertThat(page.getByText("Not valid UTF-8",
                    new Page.GetByTextOptions().setExact(true))).isVisible();
            hideCurrentEntry(page);
        });
    }

    @ManagementBrowserScenario(
            id = "PW-ENTRY-002",
            requirement = "UI design: bulk-entry preview detects stale targets, expires, and cannot be replayed",
            area = ManagementBrowserArea.ENTRY,
            risk = ManagementBrowserRisk.CRITICAL,
            action = "Preview bulk deletion, change a target, execute, replay, and execute an expired preview",
            expectedResult = "Only valid targets are deleted and stale, replayed, and expired previews receive exact outcomes",
            cleanup = "Remove remaining seeded entries and close clock, browser, server, and PostgreSQL resources",
            operations = {"previewEntryBulkDelete", "executeEntryBulkDelete"},
            evidence = {
                    ManagementBrowserEvidence.VISIBLE_RESULT,
                    ManagementBrowserEvidence.HTTP_OPERATION,
                    ManagementBrowserEvidence.DATABASE,
                    ManagementBrowserEvidence.RESOURCE_CLEANUP
            })
    @ManagementBrowserJourney(
            value = "entry-bulk-delete",
            operations = {"previewEntryBulkDelete", "executeEntryBulkDelete"})
    void bulkEntryDeletionReportsStaleTargetsAndCannotBeReplayed() throws Exception {
        MutableClock clock = new MutableClock(Instant.now(), ZoneId.of("UTC"));
        ManagementConsolePostgresFixture.run(temporaryDirectory, POSTGRES.postgres(), true, clock, context -> {
            ManagementConsolePostgresFixture.authenticate(context);
            ManagementConsolePostgresFixture.registerSetup(context);
            Page page = context.page();
            openNamespace(page);
            page.getByRole(AriaRole.TAB, new Page.GetByRoleOptions().setName("Entries")).click();

            page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Create entry").setExact(true)).click();
            Locator create = page.getByRole(AriaRole.DIALOG,
                    new Page.GetByRoleOptions().setName("Create entry"));
            create.getByLabel("Key").fill("bulk-candidate");
            create.locator("#new-entry-value").fill("bulk-secret");
            create.getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Create entry").setExact(true)).click();
            assertThat(page.getByRole(AriaRole.STATUS)).containsText("created");

            page.getByLabel("Select bulk-candidate").check();
            Response previewResponse = page.waitForResponse(
                    response -> response.url().endsWith("/entries/bulk-delete/preview"),
                    () -> page.getByRole(AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Preview selected deletion")).click());
            JsonNode previewBody = new ObjectMapper().readTree(previewResponse.text());
            Locator bulk = page.getByRole(AriaRole.DIALOG,
                    new Page.GetByRoleOptions().setName("Confirm bulk entry deletion"));
            String phrase = bulk.locator("strong").last().textContent();
            bulk.getByLabel("Type confirmation phrase").fill(phrase);
            bulk.getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Delete previewed entries")).click();
            assertThat(page.getByRole(AriaRole.STATUS)).containsText("Deleted 1 of 1");
            assertFalse(page.content().contains("bulk-secret"));
            @SuppressWarnings("unchecked")
            Map<String, Object> replay = (Map<String, Object>) page.evaluate("""
                    async ([token, phrase]) => {
                      const session = await (await fetch('/api/v1/session', {
                        credentials: 'include'
                      })).json();
                      const response = await fetch(
                        '/api/v1/setups/browser-postgres/namespaces/bG9naWNhbC1vcmRlcnM/entries/bulk-delete/execute', {
                          method: 'POST', credentials: 'include',
                          headers: {'content-type': 'application/json', 'x-peegeeq-csrf': session.csrfToken},
                          body: JSON.stringify({previewToken: token, confirmationPhrase: phrase})
                        });
                      const body = await response.json();
                      return {status: response.status, code: body.code};
                    }
                    """, List.of(
                    previewBody.path("previewToken").asText(),
                    previewBody.path("confirmationPhrase").asText()));
            assertEquals(409, ((Number) replay.get("status")).intValue());
            assertEquals("PREVIEW_TOKEN_INVALID", replay.get("code"));

            page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Create entry").setExact(true)).click();
            Locator staleCreate = page.getByRole(AriaRole.DIALOG,
                    new Page.GetByRoleOptions().setName("Create entry"));
            staleCreate.getByLabel("Key").fill("stale-bulk-candidate");
            staleCreate.locator("#new-entry-value").fill("newer-value-must-survive");
            staleCreate.getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Create entry").setExact(true)).click();
            assertThat(page.getByRole(AriaRole.STATUS)).containsText("created");
            page.getByLabel("Select stale-bulk-candidate").check();
            page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Preview selected deletion")).click();
            Locator staleBulk = page.getByRole(AriaRole.DIALOG,
                    new Page.GetByRoleOptions().setName("Confirm bulk entry deletion"));
            String stalePhrase = staleBulk.locator("strong").last().textContent();
            ManagementConsolePostgresFixture.execute(context.postgres(), """
                    UPDATE peegee_cache.cache_entries
                       SET version = version + 1,
                           value_bytes = convert_to('newer-value-must-survive', 'UTF8'),
                           updated_at = NOW()
                     WHERE namespace = 'logical-orders'
                       AND cache_key = 'stale-bulk-candidate'
                    """);
            staleBulk.getByLabel("Type confirmation phrase").fill(stalePhrase);
            staleBulk.getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Delete previewed entries")).click();
            assertThat(page.getByRole(AriaRole.STATUS))
                    .containsText("Deleted 0 of 1 previewed entries · conflicts 1");
            assertThat(page.getByText("stale-bulk-candidate",
                    new Page.GetByTextOptions().setExact(true))).isVisible();

            page.getByLabel("Select stale-bulk-candidate").check();
            Response expiringPreviewResponse = page.waitForResponse(
                    response -> response.url().endsWith("/entries/bulk-delete/preview"),
                    () -> page.getByRole(AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Preview selected deletion")).click());
            JsonNode expiringPreview = new ObjectMapper().readTree(expiringPreviewResponse.text());
            Locator expiringBulk = page.getByRole(AriaRole.DIALOG,
                    new Page.GetByRoleOptions().setName("Confirm bulk entry deletion"));
            expiringBulk.getByLabel("Type confirmation phrase")
                    .fill(expiringPreview.path("confirmationPhrase").asText());
            clock.advance(Duration.ofMinutes(5));
            page.evaluate("Date.now = () => Number.MAX_SAFE_INTEGER");
            assertThat(page.getByText("Preview expired",
                    new Page.GetByTextOptions().setExact(true))).isVisible();
            assertThat(expiringBulk.getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Delete previewed entries"))).isDisabled();
            @SuppressWarnings("unchecked")
            Map<String, Object> expired = (Map<String, Object>) page.evaluate("""
                    async ([token, phrase]) => {
                      const session = await (await fetch('/api/v1/session', {
                        credentials: 'include'
                      })).json();
                      const response = await fetch(
                        '/api/v1/setups/browser-postgres/namespaces/bG9naWNhbC1vcmRlcnM/entries/bulk-delete/execute', {
                          method: 'POST', credentials: 'include',
                          headers: {'content-type': 'application/json', 'x-peegeeq-csrf': session.csrfToken},
                          body: JSON.stringify({previewToken: token, confirmationPhrase: phrase})
                        });
                      const body = await response.json();
                      return {status: response.status, code: body.code};
                    }
                    """, List.of(
                    expiringPreview.path("previewToken").asText(),
                    expiringPreview.path("confirmationPhrase").asText()));
            assertEquals(409, ((Number) expired.get("status")).intValue());
            assertEquals("PREVIEW_TOKEN_EXPIRED", expired.get("code"));
        });
    }

    @ManagementBrowserScenario(
            id = "PW-COUNTER-001",
            requirement = "UI design: exact signed 64-bit counter lifecycle and bulk deletion",
            area = ManagementBrowserArea.COUNTER,
            risk = ManagementBrowserRisk.CRITICAL,
            action = "Browse, set, adjust, expire, persist, delete, and bulk-delete counters at exact numeric values",
            expectedResult = "The UI preserves signed 64-bit precision and PostgreSQL reflects committed counter outcomes",
            cleanup = "Delete scenario counters and close browser, server, pool, schema, and PostgreSQL resources",
            operations = {
                    "listCounters", "getCounter", "setCounter", "adjustCounter", "expireCounter",
                    "persistCounter", "deleteCounter", "previewCounterBulkDelete",
                    "executeCounterBulkDelete"
            },
            evidence = {
                    ManagementBrowserEvidence.VISIBLE_RESULT,
                    ManagementBrowserEvidence.HTTP_OPERATION,
                    ManagementBrowserEvidence.DATABASE,
                    ManagementBrowserEvidence.RESOURCE_CLEANUP
            })
    @ManagementBrowserJourney(
            value = "counter-lifecycle",
            operations = {
                    "listCounters", "getCounter", "setCounter", "adjustCounter", "expireCounter",
                    "persistCounter", "deleteCounter", "previewCounterBulkDelete",
                    "executeCounterBulkDelete"
            })
    void countersSupportExactSigned64BitLifecycleAndBulkDeletion() throws Exception {
        ManagementConsolePostgresFixture.run(temporaryDirectory, POSTGRES.postgres(), true, context -> {
            ManagementConsolePostgresFixture.authenticate(context);
            ManagementConsolePostgresFixture.registerSetup(context);
            Page page = context.page();
            page.getByRole(AriaRole.LINK,
                    new Page.GetByRoleOptions().setName("Counters").setExact(true)).click();

            String counterDetailPath = "/api/v1/setups/" + ManagementConsolePostgresFixture.SETUP_ID
                    + "/namespaces/" + encoded(ManagementConsolePostgresFixture.NAMESPACE)
                    + "/counters/" + encoded("count");
            @SuppressWarnings("unchecked")
            Map<String, Object> counterDetail = (Map<String, Object>) page.evaluate("""
                    async path => {
                      const response = await fetch(path, {credentials: 'include'});
                      return {status: response.status, body: await response.json()};
                    }
                    """, counterDetailPath);
            assertEquals(200, ((Number) counterDetail.get("status")).intValue());
            @SuppressWarnings("unchecked")
            Map<String, Object> counterBody = (Map<String, Object>) counterDetail.get("body");
            assertEquals("42", counterBody.get("value"));

            page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Manage count").setExact(true)).click();
            Locator counter = page.getByRole(AriaRole.DIALOG,
                    new Page.GetByRoleOptions().setName("Manage count"));
            counter.getByLabel("Exact decimal value").fill("9223372036854775807");
            counter.getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Set exact value")).click();
            assertThat(page.getByRole(AriaRole.STATUS)).containsText("9,223,372,036,854,775,807");
            counter.getByLabel("Signed adjustment").fill("1");
            counter.getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Apply adjustment")).click();
            assertThat(page.getByRole(AriaRole.ALERT)).containsText("COUNTER_OVERFLOW");
            assertThat(counter.getByLabel("Exact decimal value"))
                    .hasValue("9223372036854775807");
            counter.getByLabel("Signed adjustment").fill("-1");
            counter.getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Apply adjustment")).click();
            assertThat(page.getByRole(AriaRole.STATUS)).containsText("9,223,372,036,854,775,806");
            counter.getByLabel("TTL milliseconds").fill("60000");
            counter.getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Set counter TTL")).click();
            counter.getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Make counter persistent")).click();
            counter.getByLabel("Confirm counter key").fill("count");
            counter.getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Delete current version")).click();
            assertThat(page.getByRole(AriaRole.STATUS)).containsText("deleted");

            page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Create counter")).click();
            Locator create = page.getByRole(AriaRole.DIALOG,
                    new Page.GetByRoleOptions().setName("Create counter"));
            create.getByLabel("Namespace").fill(ManagementConsolePostgresFixture.NAMESPACE);
            create.getByLabel("Key").fill("bulk-counter");
            create.getByLabel("Exact decimal value").fill("-9223372036854775808");
            create.getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Set exact value")).click();
            assertThat(page.getByRole(AriaRole.STATUS)).containsText("created");
            page.getByRole(AriaRole.DIALOG,
                            new Page.GetByRoleOptions().setName("Manage bulk-counter"))
                    .getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Close")).click();
            page.getByLabel("Select logical-orders/bulk-counter").check();
            page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Preview selected counter deletion")).click();
            Locator bulk = page.getByRole(AriaRole.DIALOG,
                    new Page.GetByRoleOptions().setName("Confirm counter deletion"));
            String phrase = bulk.locator("strong").textContent();
            bulk.getByLabel("Type confirmation phrase").fill(phrase);
            bulk.getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Delete previewed counters")).click();
            assertThat(page.getByRole(AriaRole.STATUS)).containsText("Deleted 1 of 1");
        });
    }

    @ManagementBrowserScenario(
            id = "PW-LOCK-001",
            requirement = "UI design: lock-owner masking and fencing-safe forced release",
            area = ManagementBrowserArea.LOCK,
            risk = ManagementBrowserRisk.CRITICAL,
            action = "Inspect and reveal a lock owner, attempt stale release, then release the current lease",
            expectedResult = "Owner data is masked by default, stale fencing is rejected, and only the current lease is released",
            cleanup = "Hide owner data, release the lock, and close all fixture resources",
            operations = {"listLocks", "getLock", "revealLockOwner", "forceReleaseLock"},
            evidence = {
                    ManagementBrowserEvidence.VISIBLE_RESULT,
                    ManagementBrowserEvidence.HTTP_OPERATION,
                    ManagementBrowserEvidence.DATABASE,
                    ManagementBrowserEvidence.SENSITIVE_STATE,
                    ManagementBrowserEvidence.RESOURCE_CLEANUP
            })
    @ManagementBrowserJourney(
            value = "lock-lifecycle",
            operations = {"listLocks", "getLock", "revealLockOwner", "forceReleaseLock"})
    void lockOwnerRemainsMaskedAndStaleReleaseIsRejectedBeforeCurrentRelease() throws Exception {
        ManagementConsolePostgresFixture.run(temporaryDirectory, POSTGRES.postgres(), true, context -> {
            ManagementConsolePostgresFixture.authenticate(context);
            ManagementConsolePostgresFixture.registerSetup(context);
            Page page = context.page();
            page.getByRole(AriaRole.LINK,
                    new Page.GetByRoleOptions().setName("Locks").setExact(true)).click();
            page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Manage lease")).click();
            Locator lock = page.getByRole(AriaRole.DIALOG,
                    new Page.GetByRoleOptions().setName("Manage lease"));
            lock.getByLabel("Reason (optional)").fill("real browser verification");
            lock.getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Reveal owner")).click();
            assertThat(page.getByText("owner-secret", new Page.GetByTextOptions().setExact(true)))
                    .isVisible();
            lock.getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Hide owner")).click();
            lock.getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Force release")).click();

            ManagementConsolePostgresFixture.execute(context.postgres(), """
                    UPDATE peegee_cache.cache_locks
                       SET version = version + 1, fencing_token = fencing_token + 1
                     WHERE namespace = 'logical-orders' AND lock_key = 'lease'
                    """);
            Locator release = page.getByRole(AriaRole.DIALOG,
                    new Page.GetByRoleOptions().setName("Release current lock version?"));
            release.getByLabel("Confirm lock key").fill("lease");
            release.getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Release current version")).click();
            assertThat(page.getByRole(AriaRole.ALERT)).containsText("VERSION_MISMATCH");
            release.getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Cancel")).click();
            page.getByRole(AriaRole.DIALOG,
                            new Page.GetByRoleOptions().setName("Manage lease"))
                    .getByRole(AriaRole.BUTTON,
                            new Locator.GetByRoleOptions().setName("Force release")).click();
            Locator currentRelease = page.getByRole(AriaRole.DIALOG,
                    new Page.GetByRoleOptions().setName("Release current lock version?"));
            currentRelease.getByLabel("Confirm lock key").fill("lease");
            currentRelease.getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Release current version")).click();
            assertThat(page.getByRole(AriaRole.STATUS)).containsText("Lock released");
            assertThat(page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Manage lease"))).hasCount(0);
        });
    }

    @ManagementBrowserScenario(
            id = "PW-PUBSUB-001",
            requirement = "UI design: real Pub/Sub subscription, publication, resume, reveal, and stop lifecycle",
            area = ManagementBrowserArea.PUBSUB,
            risk = ManagementBrowserRisk.CRITICAL,
            action = "Subscribe, publish, resume a stream, reveal its payload, and stop the subscription",
            expectedResult = "The real transports deliver one masked message, reveal on demand, resume correctly, and close cleanly",
            cleanup = "Stop the subscription, clear payload state, and close browser, transports, server, and PostgreSQL resources",
            operations = {
                    "createPubSubSubscription", "streamPubSubMessages", "revealPubSubPayload",
                    "deletePubSubSubscription", "publishPubSubMessage"
            },
            evidence = {
                    ManagementBrowserEvidence.VISIBLE_RESULT,
                    ManagementBrowserEvidence.HTTP_OPERATION,
                    ManagementBrowserEvidence.DATABASE,
                    ManagementBrowserEvidence.SENSITIVE_STATE,
                    ManagementBrowserEvidence.RESOURCE_CLEANUP
            })
    @ManagementBrowserJourney(
            value = "pubsub-lifecycle",
            operations = {
                    "createPubSubSubscription", "streamPubSubMessages", "revealPubSubPayload",
                    "deletePubSubSubscription", "publishPubSubMessage"
            })
    void pubSubSubscribesPublishesResumesRevealsAndStopsAgainstPostgres() throws Exception {
        ManagementConsolePostgresFixture.run(temporaryDirectory, POSTGRES.postgres(), true, context -> {
            ManagementConsolePostgresFixture.authenticate(context);
            ManagementConsolePostgresFixture.registerSetup(context);
            Page page = context.page();
            page.getByRole(AriaRole.LINK,
                    new Page.GetByRoleOptions().setName("Pub/Sub").setExact(true)).click();
            page.getByLabel("Channel", new Page.GetByLabelOptions().setExact(true))
                    .fill("acceptance-channel");
            page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Start subscription")).click();
            page.getByLabel("Publish channel").fill("acceptance-channel");
            page.getByLabel("Payload").fill("pubsub-secret");
            page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Publish").setExact(true)).click();
            assertThat(page.getByRole(AriaRole.STATUS)).containsText("Accepted by PostgreSQL");
            page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Reveal payload")).click();
            assertThat(page.getByText("pubsub-secret", new Page.GetByTextOptions().setExact(true)))
                    .isVisible();
            page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Hide payload")).click();

            String csrf = (String) page.evaluate("""
                    async () => (await (await fetch('/api/v1/session', {
                      credentials: 'include'
                    })).json()).csrfToken
                    """);
            context.browserContext().setOffline(true);
            assertThat(page.getByText("Non-durable · STALE · bounded to 20 messages",
                    new Page.GetByTextOptions().setExact(true))).isVisible();
            assertEquals(200, publishOutsideBrowserNetwork(
                    context, csrf, "acceptance-channel", "retained-while-disconnected"));
            context.browserContext().setOffline(false);
            assertThat(page.getByText("Non-durable · CONNECTED · bounded to 20 messages",
                    new Page.GetByTextOptions().setExact(true))).isVisible();
            assertThat(page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Reveal payload"))).hasCount(2);
            page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Reveal payload")).first().click();
            assertThat(page.getByText("retained-while-disconnected",
                    new Page.GetByTextOptions().setExact(true))).isVisible();
            page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Hide payload")).click();
            page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Stop subscription")).click();
            assertThat(page.getByRole(AriaRole.STATUS)).containsText("discarded");
        });
    }

    @ManagementBrowserScenario(
            id = "PW-MONITOR-001",
            requirement = "UI design: SSE and WebSocket interruption is visible and recovery does not require reload",
            area = ManagementBrowserArea.MONITORING,
            risk = ManagementBrowserRisk.HIGH,
            action = "Open live transports, interrupt them, restore connectivity, and observe resumed events",
            expectedResult = "The shell exposes interruption, reconnects without reload, and deduplicates resumed events",
            cleanup = "Close SSE, WebSocket, browser, server, and PostgreSQL fixture resources",
            operations = {"streamMetrics", "monitoringWebSocket"},
            evidence = {
                    ManagementBrowserEvidence.VISIBLE_RESULT,
                    ManagementBrowserEvidence.HTTP_OPERATION,
                    ManagementBrowserEvidence.RESOURCE_CLEANUP
            })
    @ManagementBrowserJourney(
            value = "live-recovery",
            operations = {"streamMetrics", "monitoringWebSocket"})
    void liveTransportsExposeInterruptionAndRecoverWithoutReloadingThePage() throws Exception {
        ManagementConsolePostgresFixture.run(temporaryDirectory, POSTGRES.postgres(), true, context -> {
            ManagementConsolePostgresFixture.authenticate(context);
            ManagementConsolePostgresFixture.registerSetup(context);
            Page page = context.page();
            page.getByLabel("Open notifications").click();
            assertThat(page.getByText("Live", new Page.GetByTextOptions().setExact(true))).isVisible();
            page.getByRole(AriaRole.LINK,
                    new Page.GetByRoleOptions().setName("Monitoring").setExact(true)).click();
            assertThat(page.getByText("Live metrics connected",
                    new Page.GetByTextOptions().setExact(true))).isVisible();
            assertEquals(204, postSetupAction(page, "detach"));
            assertThat(page.getByText("Live metrics interrupted; displayed values may be stale",
                    new Page.GetByTextOptions().setExact(true))).isVisible();
            assertThat(page.getByText("Live stale",
                    new Page.GetByTextOptions().setExact(true))).isVisible();
            assertEquals(200, postSetupAction(page, "connect"));
            assertThat(page.getByText("Live metrics connected",
                    new Page.GetByTextOptions().setExact(true))).isVisible();
            assertThat(page.getByText("Live", new Page.GetByTextOptions().setExact(true))).isVisible();
            assertEquals(200, postJson(page,
                    "/api/v1/setups/browser-postgres/pubsub/publish",
                    "{\"channel\":\"live-recovery\",\"payload\":\"not-visible\"}"));
            Locator resourceEvents = page.getByRole(AriaRole.LISTITEM)
                    .filter(new Locator.FilterOptions().setHasText("resource changed"));
            assertThat(resourceEvents).hasCount(1);
        });
    }

    @ManagementBrowserScenario(
            id = "PW-HARDEN-002",
            requirement = "UI design: populated primary workflows remain keyboard accessible and responsive",
            area = ManagementBrowserArea.HARDENING,
            risk = ManagementBrowserRisk.HIGH,
            action = "Navigate populated routes by keyboard, resize the viewport, and exercise dialog focus behavior",
            expectedResult = "Primary content remains reachable and dialog focus is trapped, escaped, and restored",
            cleanup = "Close dialogs, restore context state, and close all browser and fixture resources",
            evidence = {
                    ManagementBrowserEvidence.VISIBLE_RESULT,
                    ManagementBrowserEvidence.RESOURCE_CLEANUP
            })
    @ManagementBrowserJourney(
            value = "accessibility-and-responsive",
            operations = {})
    void populatedWorkflowsRemainKeyboardReachableAndResponsive() throws Exception {
        ManagementConsolePostgresFixture.run(temporaryDirectory, POSTGRES.postgres(), true, context -> {
            ManagementConsolePostgresFixture.authenticate(context);
            ManagementConsolePostgresFixture.registerSetup(context);
            Page page = context.page();

            Map<String, String> primaryRoutes = Map.ofEntries(
                    Map.entry("/ui/", "Overview"),
                    Map.entry("/ui/setups", "Setups"),
                    Map.entry("/ui/namespaces", "Namespaces"),
                    Map.entry("/ui/namespaces/" + encoded(ManagementConsolePostgresFixture.NAMESPACE),
                            ManagementConsolePostgresFixture.NAMESPACE),
                    Map.entry("/ui/keys", "Key Browser"),
                    Map.entry("/ui/keys/" + encoded(ManagementConsolePostgresFixture.NAMESPACE)
                            + "/" + encoded("customer:1"), "customer:1"),
                    Map.entry("/ui/counters", "Counters"),
                    Map.entry("/ui/locks", "Locks"),
                    Map.entry("/ui/pubsub", "Pub/Sub"),
                    Map.entry("/ui/monitoring", "Monitoring"),
                    Map.entry("/ui/settings", "Settings"));
            for (Map.Entry<String, String> route : primaryRoutes.entrySet()) {
                assertEquals(200, page.navigate(context.origin() + route.getKey()).status());
                assertThat(page.getByRole(AriaRole.HEADING,
                        new Page.GetByRoleOptions().setName(route.getValue()).setExact(true)))
                        .isVisible();
                for (int[] viewport : List.of(new int[]{390, 844}, new int[]{1440, 900})) {
                    page.setViewportSize(viewport[0], viewport[1]);
                    assertViewportSurfacesContained(page, route.getKey() + " at "
                            + viewport[0] + "x" + viewport[1]);
                    assertNoAxeViolations(page);
                }
            }

            assertEquals(200, page.navigate(context.origin() + "/ui/counters").status());
            page.setViewportSize(390, 844);
            page.getByRole(AriaRole.LINK,
                    new Page.GetByRoleOptions().setName("Counters").setExact(true)).focus();
            page.keyboard().press("Enter");
            assertThat(page.getByRole(AriaRole.HEADING,
                    new Page.GetByRoleOptions().setName("Counters").setExact(true))).isVisible();
            for (int[] viewport : List.of(
                    new int[]{360, 640}, new int[]{390, 844}, new int[]{768, 1024},
                    new int[]{1024, 768}, new int[]{1440, 900}, new int[]{1920, 1080})) {
                page.setViewportSize(viewport[0], viewport[1]);
                assertViewportSurfacesContained(page,
                        "Counters at " + viewport[0] + "x" + viewport[1]);
            }
            assertNoAxeViolations(page);
            Locator manage = page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Manage count"));
            manage.focus();
            page.keyboard().press("Enter");
            Locator dialog = page.getByRole(AriaRole.DIALOG,
                    new Page.GetByRoleOptions().setName("Manage count"));
            assertThat(dialog).isVisible();
            assertNoAxeViolations(page);
            page.keyboard().press("Shift+Tab");
            assertTrue((Boolean) dialog.evaluate("dialog => dialog.contains(document.activeElement)"));
            page.keyboard().press("Escape");
            assertThat(dialog).hasCount(0);
            assertTrue((Boolean) manage.evaluate("button => document.activeElement === button"));
        });
    }

    @ManagementBrowserScenario(
            id = "PW-HARDEN-003",
            requirement = "UI design: credentials and revealed values must not leak across browser, diagnostics, or audit surfaces",
            area = ManagementBrowserArea.HARDENING,
            risk = ManagementBrowserRisk.CRITICAL,
            action = "Reveal a value and scan DOM, URL, history, storage, console, failures, and durable audit data",
            expectedResult = "Sensitive canaries appear only in the authorized transient control and nowhere persistent",
            cleanup = "Hide the revealed value, clear sensitive DOM state, and close all fixture resources",
            evidence = {
                    ManagementBrowserEvidence.VISIBLE_RESULT,
                    ManagementBrowserEvidence.DURABLE_AUDIT,
                    ManagementBrowserEvidence.SENSITIVE_STATE,
                    ManagementBrowserEvidence.RESOURCE_CLEANUP
            })
    @ManagementBrowserJourney(
            value = "cross-surface-leakage",
            operations = {})
    void revealedValuesAndCredentialsAreAbsentFromPersistentBrowserAndAuditSurfaces() throws Exception {
        ManagementConsolePostgresFixture.run(temporaryDirectory, POSTGRES.postgres(), true, context -> {
            ManagementConsolePostgresFixture.authenticate(context);
            ManagementConsolePostgresFixture.registerSetup(context);
            Page page = context.page();
            openEntry(page, "customer:1");
            page.getByLabel("Reveal reason (optional)").fill("leakage verification");
            page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Reveal value")).click();
            assertThat(page.getByText("stored-value", new Page.GetByTextOptions().setExact(true)))
                    .isVisible();
            page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Hide value")).click();

            String browserState = String.valueOf(page.evaluate("""
                    JSON.stringify({
                      url: location.href,
                      local: Object.values(localStorage),
                      session: Object.values(sessionStorage),
                      cookie: document.cookie,
                      html: document.documentElement.outerHTML
                    })
                    """));
            assertFalse(browserState.contains("stored-value"));
            assertFalse(browserState.contains(ManagementConsolePostgresFixture.DATABASE_PASSWORD));
            assertFalse(browserState.contains(context.bootstrapToken()));
            assertFalse(String.valueOf(page.evaluate("document.cookie")).contains("PGQMGMTSESSION"));
            String accessibilityTree = page.locator("body").ariaSnapshot();
            assertFalse(accessibilityTree.contains("stored-value"));
            assertFalse(accessibilityTree.contains(ManagementConsolePostgresFixture.DATABASE_PASSWORD));
            assertFalse(accessibilityTree.contains(context.bootstrapToken()));

            Path screenshot = temporaryDirectory.resolve("cross-surface-clean.png");
            page.screenshot(new Page.ScreenshotOptions().setFullPage(true).setPath(screenshot));
            String screenshotBytes = new String(Files.readAllBytes(screenshot),
                    StandardCharsets.ISO_8859_1);
            assertFalse(screenshotBytes.contains("stored-value"));
            assertFalse(screenshotBytes.contains(ManagementConsolePostgresFixture.DATABASE_PASSWORD));
            assertFalse(screenshotBytes.contains(context.bootstrapToken()));

            String audit = Files.readString(context.auditPath());
            assertFalse(audit.contains("stored-value"));
            assertFalse(audit.contains(ManagementConsolePostgresFixture.DATABASE_PASSWORD));
            assertFalse(audit.contains(context.bootstrapToken()));
            assertEquals(List.of(), context.diagnostics().failedResponses());
        });
    }

    @ManagementBrowserScenario(
            id = "PW-HARDEN-004",
            requirement = "UI design: deterministic shutdown closes active browser transports and returns resource gauges to baseline",
            area = ManagementBrowserArea.HARDENING,
            risk = ManagementBrowserRisk.CRITICAL,
            action = "Open Pub/Sub, SSE, and WebSocket resources and then trigger the complete fixture shutdown path",
            expectedResult = "All transports close and server resource gauges return to zero",
            cleanup = "Verify zero gauges and close browser, server, pool, schema, and PostgreSQL resources",
            evidence = {
                    ManagementBrowserEvidence.VISIBLE_RESULT,
                    ManagementBrowserEvidence.RESOURCE_CLEANUP
            })
    @ManagementBrowserJourney(
            value = "deterministic-shutdown",
            operations = {})
    void activeBrowserTransportsAreClosedAndResourceGaugesReturnToZero() throws Exception {
        ManagementConsolePostgresFixture.run(temporaryDirectory, POSTGRES.postgres(), true, context -> {
            ManagementConsolePostgresFixture.authenticate(context);
            ManagementConsolePostgresFixture.registerSetup(context);
            Page page = context.page();
            page.getByRole(AriaRole.LINK,
                    new Page.GetByRoleOptions().setName("Pub/Sub").setExact(true)).click();
            page.getByLabel("Channel", new Page.GetByLabelOptions().setExact(true))
                    .fill("shutdown-channel");
            page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Start subscription")).click();
            assertThat(page.getByRole(AriaRole.HEADING,
                    new Page.GetByRoleOptions().setName("Subscription: shutdown-channel")))
                    .isVisible();
            assertThat(page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Stop subscription"))).isVisible();
            // The fixture closes the browser first, then the application and asserts the shutdown leak counter.
        });
    }

    private static void openNamespace(Page page) {
        page.getByRole(AriaRole.LINK,
                new Page.GetByRoleOptions().setName("Namespaces").setExact(true)).click();
        page.getByRole(AriaRole.LINK,
                new Page.GetByRoleOptions().setName(ManagementConsolePostgresFixture.NAMESPACE)
                        .setExact(true)).click();
    }

    private static int postSetupAction(Page page, String action) {
        return ((Number) page.evaluate("""
                async action => {
                  const sessionResponse = await fetch('/api/v1/session', {credentials: 'include'});
                  const session = await sessionResponse.json();
                  const response = await fetch(`/api/v1/setups/browser-postgres/${action}`, {
                    method: 'POST',
                    credentials: 'include',
                    headers: {'x-peegeeq-csrf': session.csrfToken}
                  });
                  return response.status;
                }
                """, action)).intValue();
    }

    private static int postJson(Page page, String path, String body) {
        return ((Number) page.evaluate("""
                async ([path, body]) => {
                  const sessionResponse = await fetch('/api/v1/session', {credentials: 'include'});
                  const session = await sessionResponse.json();
                  const response = await fetch(path, {
                    method: 'POST', credentials: 'include',
                    headers: {'content-type': 'application/json', 'x-peegeeq-csrf': session.csrfToken},
                    body
                  });
                  return response.status;
                }
                """, List.of(path, body))).intValue();
    }

    private static void openEntry(Page page, String key) {
        openNamespace(page);
        page.getByRole(AriaRole.TAB, new Page.GetByRoleOptions().setName("Entries")).click();
        page.getByRole(AriaRole.LINK,
                new Page.GetByRoleOptions().setName(key).setExact(true)).click();
        assertThat(page.getByRole(AriaRole.HEADING,
                new Page.GetByRoleOptions().setName(key).setExact(true))).isVisible();
    }

    private static void revealCurrentEntry(Page page) {
        page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Reveal value").setExact(true)).click();
    }

    private static void hideCurrentEntry(Page page) {
        page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Hide value").setExact(true)).click();
        assertThat(page.getByText("Value hidden", new Page.GetByTextOptions().setExact(true)))
                .isVisible();
    }

    private static void confirmRowAction(Page page, String action) {
        setupRow(page).getByRole(AriaRole.BUTTON,
                new Locator.GetByRoleOptions().setName(action).setExact(true)).click();
        Locator dialog = page.getByRole(AriaRole.DIALOG,
                new Page.GetByRoleOptions().setName(action + " "
                        + ManagementConsolePostgresFixture.SETUP_NAME + "?"));
        dialog.getByRole(AriaRole.BUTTON,
                new Locator.GetByRoleOptions().setName(action).setExact(true)).click();
    }

    private static int publishOutsideBrowserNetwork(
            ManagementConsolePostgresFixture.Context context,
            String csrf,
            String channel,
            String payload) throws Exception {
        String cookie = context.browserContext().cookies().stream()
                .filter(candidate -> candidate.name.equals("PGQMGMTSESSION"))
                .findFirst()
                .orElseThrow()
                .value;
        String body = new ObjectMapper().createObjectNode()
                .put("channel", channel)
                .put("payload", payload)
                .toString();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(java.net.URI.create(context.origin()
                        + "/api/v1/setups/" + ManagementConsolePostgresFixture.SETUP_ID
                        + "/pubsub/publish"))
                .header("content-type", "application/json")
                .header("cookie", "PGQMGMTSESSION=" + cookie)
                .header("origin", context.origin())
                .header("x-peegeeq-csrf", csrf)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.discarding())
                .statusCode();
    }

    private static Locator setupRow(Page page) {
        return page.getByRole(AriaRole.ROW)
                .filter(new Locator.FilterOptions().setHasText(ManagementConsolePostgresFixture.SETUP_NAME));
    }

    private static Locator metric(Page page, String label) {
        return page.locator(".metric-card")
                .filter(new Locator.FilterOptions().setHasText(label));
    }

    private static void assertViewportSurfacesContained(Page page, String context) {
        String overflow = (String) page.evaluate("""
                () => Array.from(document.querySelectorAll(
                    'body, #root, .console, .console__header, .console__sidebar, .console__main, .workspace, .table-scroll'))
                  .filter((element) => {
                    const box = element.getBoundingClientRect();
                    return box.left < -1 || box.right > window.innerWidth + 1;
                  })
                  .map((element) => {
                    const box = element.getBoundingClientRect();
                    return `${element.tagName.toLowerCase()}.${element.className || '-'}:${Math.round(box.left)}..${Math.round(box.right)}`;
                  })
                  .join(' | ')
                """);
        assertEquals("", overflow, () -> context + " has a surface outside the viewport: " + overflow);
    }

    private static void assertNoAxeViolations(Page page) throws Exception {
        Path axe = List.of(
                        Path.of("peegee-cache-management-ui", "node_modules", "axe-core", "axe.min.js"),
                        Path.of("..", "peegee-cache-management-ui", "node_modules", "axe-core", "axe.min.js"))
                .stream()
                .map(Path::toAbsolutePath)
                .filter(Files::isRegularFile)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "axe-core is absent; build peegee-cache-management-ui before browser acceptance"));
        page.evaluate(Files.readString(axe));
        String violations = (String) page.evaluate("""
                async () => JSON.stringify((await axe.run(document, {
                  runOnly: {type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']}
                })).violations.map(violation => ({
                  id: violation.id,
                  impact: violation.impact,
                  targets: violation.nodes.map(node => node.target)
                })))
                """);
        assertEquals("[]", violations, () -> "axe-core violations: " + violations);
    }

    private static String encoded(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static final class MutableClock extends Clock {
        private volatile Instant instant;
        private final ZoneId zone;

        private MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId requestedZone) {
            return new MutableClock(instant, requestedZone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
