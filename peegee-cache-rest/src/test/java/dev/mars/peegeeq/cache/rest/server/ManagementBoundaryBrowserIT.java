package dev.mars.peegeeq.cache.rest.server;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

class ManagementBoundaryBrowserIT {

    @RegisterExtension
    static final ManagementBrowserPostgresWorker POSTGRES = new ManagementBrowserPostgresWorker();

    @TempDir
    Path temporaryDirectory;

    @ManagementBrowserScenario(id = "PW-COUNTER-046",
            requirement = "UI design: counter inventory follows the server's opaque pagination cursor",
            area = ManagementBrowserArea.COUNTER, risk = ManagementBrowserRisk.HIGH,
            action = "Seed more than one counter page, open Counters, and request the next page",
            expectedResult = "The browser appends counters available only after following the server cursor",
            cleanup = "Reset the seeded counters and close browser, server, and PostgreSQL resources",
            operations = {"listCounters"},
            evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION,
                    ManagementBrowserEvidence.DATABASE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void counterPaginationLoadsTheNextServerCursorPage() throws Exception {
        ManagementConsolePostgresFixture.run(
                temporaryDirectory, POSTGRES.postgres(), true, context -> {
                    ManagementConsolePostgresFixture.execute(context.postgres(), """
                            INSERT INTO peegee_cache.cache_counters
                                (namespace, counter_key, counter_value, version)
                            SELECT 'logical-orders', 'pager-' || lpad(value::text, 3, '0'), value, 1
                              FROM generate_series(1, 60) value
                            """);
                    prepare(context);
                    Page page = context.page();
                    page.getByRole(AriaRole.LINK,
                            new Page.GetByRoleOptions().setName("Counters").setExact(true)).click();
                    assertThat(page.getByText("pager-060", new Page.GetByTextOptions().setExact(true)))
                            .hasCount(0);
                    page.getByRole(AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Load more counters")).click();
                    assertThat(page.getByText("pager-060", new Page.GetByTextOptions().setExact(true)))
                            .isVisible();
                });
    }

    @ManagementBrowserScenario(id = "PW-PUBSUB-032",
            requirement = "Management capability limit: a payload exactly at the advertised UTF-8 byte limit is accepted",
            area = ManagementBrowserArea.PUBSUB, risk = ManagementBrowserRisk.CRITICAL,
            action = "Enter a 7,500-byte payload and publish it through the packaged console",
            expectedResult = "The boundary payload remains enabled and PostgreSQL accepts the publication",
            cleanup = "Clear the payload and close browser, server, audit, and PostgreSQL resources",
            operations = {"publishPubSubMessage"},
            evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION,
                    ManagementBrowserEvidence.DURABLE_AUDIT, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void pubSubPayloadAtAdvertisedByteLimitPublishes() throws Exception {
        ManagementConsolePostgresFixture.run(
                temporaryDirectory, POSTGRES.postgres(), true, context -> {
                    prepare(context);
                    Page page = openPubSub(context);
                    page.getByLabel("Publish channel").fill("boundary-channel");
                    page.getByLabel("Payload").fill("x".repeat(7_500));
                    assertThat(page.getByText("7500 raw · 7500 / 7500 wire bytes",
                            new Page.GetByTextOptions().setExact(true))).isVisible();
                    assertThat(page.getByRole(AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Publish").setExact(true))).isEnabled();
                    page.getByRole(AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Publish").setExact(true)).click();
                    assertThat(page.getByRole(AriaRole.STATUS)).containsText("Accepted by PostgreSQL");
                });
    }

    @ManagementBrowserScenario(id = "PW-PUBSUB-033",
            requirement = "Management capability limit: payloads above the advertised UTF-8 byte limit are blocked",
            area = ManagementBrowserArea.PUBSUB, risk = ManagementBrowserRisk.CRITICAL,
            action = "Enter a payload one byte above the 7,500-byte limit",
            expectedResult = "The exact byte count is visible and Publish remains disabled without an HTTP request",
            cleanup = "Clear the oversized payload and close all fixture resources",
            evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void pubSubPayloadOneByteAboveAdvertisedLimitIsBlockedClientSide() throws Exception {
        ManagementConsolePostgresFixture.run(
                temporaryDirectory, POSTGRES.postgres(), true, context -> {
                    prepare(context);
                    Page page = openPubSub(context);
                    page.getByLabel("Publish channel").fill("boundary-channel");
                    page.getByLabel("Payload").fill("x".repeat(7_501));
                    assertThat(page.getByText("7501 raw · 7501 / 7500 wire bytes",
                            new Page.GetByTextOptions().setExact(true))).isVisible();
                    assertThat(page.getByRole(AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Publish").setExact(true))).isDisabled();
                });
    }

    private static void prepare(ManagementConsolePostgresFixture.Context context) {
        ManagementConsolePostgresFixture.authenticate(context);
        ManagementConsolePostgresFixture.registerSetup(context);
    }

    private static Page openPubSub(ManagementConsolePostgresFixture.Context context) {
        Page page = context.page();
        page.getByRole(AriaRole.LINK,
                new Page.GetByRoleOptions().setName("Pub/Sub").setExact(true)).click();
        assertThat(page.getByRole(AriaRole.HEADING,
                new Page.GetByRoleOptions().setName("Publish").setExact(true))).isVisible();
        return page;
    }
}
