package dev.mars.peegeeq.cache.rest.server;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Wave P4 real-browser scenarios for counters and exact signed numeric behavior. */
class ManagementCounterBrowserIT {
    @RegisterExtension static final ManagementBrowserPostgresWorker POSTGRES = new ManagementBrowserPostgresWorker();
    @TempDir Path temporaryDirectory;

    private static final List<String> ACTIONS = List.of(
            "state the exact signed 64-bit boundary", "show canonical counter columns", "list the seeded counter",
            "render value 42 exactly", "render version 4 exactly", "render persistent TTL explicitly",
            "filter by exact namespace", "filter by key prefix", "trim counter filters", "show the counter empty state",
            "expose per-row selection", "open seeded counter management", "open counter creation", "show creation identity fields",
            "create counter zero", "create signed maximum", "create signed minimum", "create a positive counter",
            "create a negative counter", "reject a fractional counter", "require creation namespace", "require creation key",
            "disable empty exact-value submission", "create a counter with TTL", "prefill the managed exact value",
            "disable an empty adjustment", "disable a zero adjustment", "apply a positive adjustment", "apply a negative adjustment",
            "reject signed overflow", "reject signed underflow", "set an existing counter exactly", "reject a stale set version",
            "require positive TTL", "set counter TTL", "make a counter persistent", "require exact key deletion confirmation",
            "delete the current counter version", "disable bulk preview without selection", "preview one selected counter",
            "disable bulk execution for a wrong phrase", "enable bulk execution for the exact phrase",
            "execute selected counter deletion", "clear selection when filters reload");

    static List<ManagementBrowserCase> scenarios() {
        return java.util.stream.IntStream.range(0, 44).mapToObj(i -> new ManagementBrowserCase(
                "PW-COUNTER-%03d".formatted(i + 2), "P4 counter contract: " + ACTIONS.get(i),
                ManagementBrowserArea.COUNTER, i >= 14 ? ManagementBrowserRisk.CRITICAL : ManagementBrowserRisk.HIGH,
                ACTIONS.get(i), "The packaged console must " + ACTIONS.get(i) + ".",
                "Reset PostgreSQL and close all counter, browser, server, and pool resources", operations(i),
                operations(i).stream().anyMatch(op -> !op.equals("listCounters") && !op.equals("getCounter"))
                        ? Set.of(ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE, ManagementBrowserEvidence.DURABLE_AUDIT, ManagementBrowserEvidence.RESOURCE_CLEANUP)
                        : Set.of(ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.RESOURCE_CLEANUP))).toList();
    }

    private static List<String> operations(int i) {
        if (i < 14 || i == 43) return List.of("listCounters");
        if (i <= 18 || i == 23) return List.of("listCounters", "setCounter");
        if (i <= 22) return List.of("listCounters");
        if (i < 27 || i == 33 || i == 36) return List.of("listCounters");
        if (i == 27 || i == 28 || i == 29 || i == 30) return List.of("listCounters", "adjustCounter");
        if (i == 31 || i == 32) return List.of("listCounters", "setCounter");
        if (i == 34) return List.of("listCounters", "expireCounter");
        if (i == 35) return List.of("listCounters", "persistCounter");
        if (i == 37) return List.of("listCounters", "deleteCounter");
        if (i == 38) return List.of("listCounters");
        if (i >= 39 && i <= 41) return List.of("listCounters", "previewCounterBulkDelete");
        return List.of("listCounters", "previewCounterBulkDelete", "executeCounterBulkDelete");
    }

    @ParameterizedTest(name="{0}") @MethodSource("dev.mars.peegeeq.cache.rest.server.ManagementBrowserSelection#counterScenarios")
    void counterScenario(ManagementBrowserCase scenario) throws Exception {
        int i=Integer.parseInt(scenario.id().substring(scenario.id().length()-3))-2;
        ManagementConsolePostgresFixture.run(temporaryDirectory,POSTGRES.postgres(),true,scenario.operations(),c->{
            if (i == 29 || i == 30) c.diagnostics().expectFailedResponse(409,
                    "/api/v1/setups/{setupId}/namespaces/{encodedNamespace}/counters/{encodedKey}/increment");
            if (i == 32) c.diagnostics().expectFailedResponse(412,
                    "/api/v1/setups/{setupId}/namespaces/{encodedNamespace}/counters/{encodedKey}");
            seed(i,c);
            Page p=c.page(); p.getByRole(AriaRole.LINK,new Page.GetByRoleOptions().setName("Counters").setExact(true)).click();
            assertThat(p.getByRole(AriaRole.HEADING,new Page.GetByRoleOptions().setName("Counters").setExact(true))).isVisible(); verify(i,c);
        });
    }

    private static void verify(int i, ManagementConsolePostgresFixture.Context c) throws Exception {
        Page p=c.page();
        switch(i){
            case 0->assertThat(p.getByText("Exact signed 64-bit administration",exact())).isVisible();
            case 1->assertEquals(List.of("Selection","Namespace","Key","Value","Version","Updated","TTL","Actions"),p.locator("thead th").allTextContents());
            case 2->assertThat(row(p,"count")).isVisible(); case 3->assertEquals("42",row(p,"count").locator("td").nth(3).textContent());
            case 4->assertEquals("4",row(p,"count").locator("td").nth(4).textContent()); case 5->assertThat(row(p,"count")).containsText("Persistent");
            case 6->{p.getByLabel("Namespace").fill("logical-orders");assertThat(row(p,"count")).isVisible();}
            case 7->{p.getByLabel("Key prefix").fill("cou");assertThat(row(p,"count")).isVisible();}
            case 8->{p.getByLabel("Namespace").fill("  logical-orders  ");p.getByLabel("Key prefix").fill("  cou  ");assertThat(row(p,"count")).isVisible();}
            case 9->{p.getByLabel("Key prefix").fill("absent");assertThat(p.getByText("No counters matched.",exact())).isVisible();}
            case 10->assertThat(p.getByLabel("Select logical-orders/count")).isVisible(); case 11->assertThat(manage(p)).isVisible();
            case 12->assertThat(create(p)).isVisible(); case 13->{Locator d=create(p);assertThat(d.getByLabel("Namespace")).isVisible();assertThat(d.getByLabel("Key")).isVisible();}
            case 14->{createValue(p,"zero","0","");assertCounterValue(c,"zero","0");} case 15->{createValue(p,"max","9223372036854775807","");assertCounterValue(c,"max","9223372036854775807");} case 16->{createValue(p,"min","-9223372036854775808","");assertCounterValue(c,"min","-9223372036854775808");}
            case 17->{createValue(p,"positive","17","");assertCounterValue(c,"positive","17");} case 18->{createValue(p,"negative","-17","");assertCounterValue(c,"negative","-17");}
            case 19->{Locator d=create(p);fillCreate(d,"fraction","1.5","");d.getByRole(AriaRole.BUTTON,new Locator.GetByRoleOptions().setName("Set exact value")).click();assertThat(p.getByRole(AriaRole.ALERT)).isVisible();}
            case 20->assertThat(create(p).getByLabel("Namespace")).hasAttribute("required",""); case 21->assertThat(create(p).getByLabel("Key")).hasAttribute("required","");
            case 22->assertThat(create(p).getByRole(AriaRole.BUTTON,new Locator.GetByRoleOptions().setName("Set exact value"))).isDisabled();
            case 23->{createValue(p,"with-ttl","9","60000");assertEquals("true",scalar(c,"SELECT expires_at IS NOT NULL FROM peegee_cache.cache_counters WHERE counter_key='with-ttl'"));} case 24->{Locator d=manage(p);assertThat(d.getByLabel("Exact decimal value")).hasValue("42");}
            case 25->assertThat(manage(p).getByRole(AriaRole.BUTTON,new Locator.GetByRoleOptions().setName("Apply adjustment"))).isDisabled();
            case 26->{Locator d=manage(p);d.getByLabel("Signed adjustment").fill("0");assertThat(d.getByRole(AriaRole.BUTTON,new Locator.GetByRoleOptions().setName("Apply adjustment"))).isDisabled();}
            case 27->{adjust(p,"8","50");assertCounterValue(c,"count","50");} case 28->{adjust(p,"-8","34");assertCounterValue(c,"count","34");}
            case 29->{Locator d=manage(p);d.getByLabel("Signed adjustment").fill("1");d.getByRole(AriaRole.BUTTON,new Locator.GetByRoleOptions().setName("Apply adjustment")).click();assertThat(p.getByRole(AriaRole.ALERT)).containsText("COUNTER_OVERFLOW");}
            case 30->{Locator d=manage(p);d.getByLabel("Signed adjustment").fill("-1");d.getByRole(AriaRole.BUTTON,new Locator.GetByRoleOptions().setName("Apply adjustment")).click();assertThat(p.getByRole(AriaRole.ALERT)).containsText("COUNTER_OVERFLOW");}
            case 31->{Locator d=manage(p);d.getByLabel("Exact decimal value").fill("100");d.getByRole(AriaRole.BUTTON,new Locator.GetByRoleOptions().setName("Set exact value")).click();assertThat(p.getByRole(AriaRole.STATUS)).containsText("100");assertCounterValue(c,"count","100");}
            case 32->{Locator d=manage(p);ManagementConsolePostgresFixture.execute(c.postgres(),"UPDATE peegee_cache.cache_counters SET version=version+1 WHERE namespace='logical-orders' AND counter_key='count'");d.getByLabel("Exact decimal value").fill("100");d.getByRole(AriaRole.BUTTON,new Locator.GetByRoleOptions().setName("Set exact value")).click();assertThat(p.getByRole(AriaRole.ALERT)).containsText("VERSION_MISMATCH");}
            case 33->assertThat(manage(p).getByLabel("TTL milliseconds")).hasAttribute("min","1");
            case 34->{Locator d=manage(p);d.getByLabel("TTL milliseconds").fill("60000");d.getByRole(AriaRole.BUTTON,new Locator.GetByRoleOptions().setName("Set counter TTL")).click();assertThat(p.getByRole(AriaRole.STATUS)).containsText("TTL set");assertEquals("true",scalar(c,"SELECT expires_at IS NOT NULL FROM peegee_cache.cache_counters WHERE counter_key='count'"));}
            case 35->{Locator d=manage(p);d.getByRole(AriaRole.BUTTON,new Locator.GetByRoleOptions().setName("Make counter persistent")).click();assertThat(p.getByRole(AriaRole.STATUS)).containsText("persisted");assertEquals("true",scalar(c,"SELECT expires_at IS NULL FROM peegee_cache.cache_counters WHERE counter_key='count'"));}
            case 36->{Locator d=manage(p);assertThat(d.getByRole(AriaRole.BUTTON,new Locator.GetByRoleOptions().setName("Delete current version"))).isDisabled();d.getByLabel("Confirm counter key").fill("wrong");assertThat(d.getByRole(AriaRole.BUTTON,new Locator.GetByRoleOptions().setName("Delete current version"))).isDisabled();}
            case 37->{Locator d=manage(p);d.getByLabel("Confirm counter key").fill("count");d.getByRole(AriaRole.BUTTON,new Locator.GetByRoleOptions().setName("Delete current version")).click();assertThat(p.getByRole(AriaRole.STATUS)).containsText("deleted");assertEquals("0",scalar(c,"SELECT count(*) FROM peegee_cache.cache_counters WHERE counter_key='count'"));}
            case 38->assertThat(p.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Preview selected counter deletion"))).isDisabled();
            case 39->assertThat(preview(p)).containsText("1 counters"); case 40->{Locator d=preview(p);d.getByLabel("Type confirmation phrase").fill("wrong");assertThat(deleteBulk(d)).isDisabled();}
            case 41->{Locator d=preview(p);d.getByLabel("Type confirmation phrase").fill(phrase(d));assertThat(deleteBulk(d)).isEnabled();}
            case 42->{Locator d=preview(p);d.getByLabel("Type confirmation phrase").fill(phrase(d));deleteBulk(d).click();assertThat(p.getByRole(AriaRole.STATUS)).containsText("Deleted 1 of 1");assertEquals("0",scalar(c,"SELECT count(*) FROM peegee_cache.cache_counters WHERE counter_key='count'"));}
            case 43->{p.getByLabel("Select logical-orders/count").check();p.getByLabel("Key prefix").fill("c");assertThat(p.getByLabel("Select logical-orders/count")).not().isChecked();}
            default->throw new IllegalArgumentException();
        }
    }

    private static void seed(int i,ManagementConsolePostgresFixture.Context c)throws Exception{if(i==29)ManagementConsolePostgresFixture.execute(c.postgres(),"UPDATE peegee_cache.cache_counters SET counter_value=9223372036854775807 WHERE counter_key='count'");if(i==30)ManagementConsolePostgresFixture.execute(c.postgres(),"UPDATE peegee_cache.cache_counters SET counter_value=-9223372036854775808 WHERE counter_key='count'");if(i==35)ManagementConsolePostgresFixture.execute(c.postgres(),"UPDATE peegee_cache.cache_counters SET expires_at=clock_timestamp()+interval '5 minutes' WHERE counter_key='count'");}
    private static String scalar(ManagementConsolePostgresFixture.Context c,String sql)throws Exception{return ManagementConsolePostgresFixture.queryScalar(c.postgres(),sql);}
    private static void assertCounterValue(ManagementConsolePostgresFixture.Context c,String key,String expected)throws Exception{assertEquals(expected,scalar(c,"SELECT counter_value FROM peegee_cache.cache_counters WHERE counter_key='"+key+"'"));}
    private static Locator row(Page p,String key){return p.getByRole(AriaRole.ROW).filter(new Locator.FilterOptions().setHasText(key));}
    private static Locator manage(Page p){p.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Manage count")).click();return p.getByRole(AriaRole.DIALOG,new Page.GetByRoleOptions().setName("Manage count"));}
    private static Locator create(Page p){p.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Create counter")).click();return p.getByRole(AriaRole.DIALOG,new Page.GetByRoleOptions().setName("Create counter"));}
    private static void fillCreate(Locator d,String key,String value,String ttl){d.getByLabel("Namespace").fill("logical-orders");d.getByLabel("Key").fill(key);d.getByLabel("Exact decimal value").fill(value);if(!ttl.isEmpty())d.getByLabel("TTL milliseconds").fill(ttl);}
    private static void createValue(Page p,String key,String value,String ttl){Locator d=create(p);fillCreate(d,key,value,ttl);d.getByRole(AriaRole.BUTTON,new Locator.GetByRoleOptions().setName("Set exact value")).click();assertThat(p.getByRole(AriaRole.STATUS)).containsText("Counter created");assertThat(row(p,key)).isVisible();}
    private static void adjust(Page p,String delta,String expected){Locator d=manage(p);d.getByLabel("Signed adjustment").fill(delta);d.getByRole(AriaRole.BUTTON,new Locator.GetByRoleOptions().setName("Apply adjustment")).click();assertThat(p.getByRole(AriaRole.STATUS)).containsText(expected);}
    private static Locator preview(Page p){p.getByLabel("Select logical-orders/count").check();p.getByRole(AriaRole.BUTTON,new Page.GetByRoleOptions().setName("Preview selected counter deletion")).click();return p.getByRole(AriaRole.DIALOG,new Page.GetByRoleOptions().setName("Confirm counter deletion"));}
    private static Locator deleteBulk(Locator d){return d.getByRole(AriaRole.BUTTON,new Locator.GetByRoleOptions().setName("Delete previewed counters"));} private static String phrase(Locator d){return d.locator("strong").textContent();}
    private static Page.GetByTextOptions exact(){return new Page.GetByTextOptions().setExact(true);}
}
