package dev.mars.peegeeq.cache.rest.server;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.options.ScreenshotAnimations;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/** Screenshot infrastructure is exercised with real Chromium and real PNG files. */
class ManagementBrowserScreenshotsIT {
    @TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.ON_SUCCESS) Path directory;

    @Test
    void capturesActualBrowserPixelsWithoutMaskingOrChangingDomState() throws Exception {
        try (Fixture fixture = new Fixture("""
                    <main style="width:500px;height:500px"><h1>Capture fixture</h1>
                    <input type="password" value="password-canary">
                    <textarea>payload-canary</textarea><pre>revealed-canary</pre>
                    <p id="secret">token-canary-AA</p>
                    <dl class="json-tree"><dt>field</dt><dd>private-AA</dd></dl>
                    <div class="value-formatter"><code>scalar-AA</code></div>
                    <div class="value-content">value-content-AA</div>
                    <div data-sensitive="true">sensitive-AA</div>
                    <div contenteditable="true">editable-AA</div>
                    <textarea id="empty"></textarea>
                    <button>Done</button></main>
                    """);
             BrowserContext context = ManagementBrowserPlaywrightSuite.browser().newContext(
                     new Browser.NewContextOptions().setViewportSize(1440, 900))) {
            var page = context.newPage();
            page.navigate(fixture.url());
            String before = page.locator("body").innerText();
            var images = ManagementBrowserScreenshots.capture(page, page.locator("main"),
                    directory, "PW-TEST-001-01");
            assertEquals(2, images.size());
            var viewport = ImageIO.read(images.getFirst().path().toFile());
            assertEquals(1440, viewport.getWidth());
            assertEquals(900, viewport.getHeight());
            var focused = ImageIO.read(images.getLast().path().toFile());
            assertEquals(500, focused.getWidth());
            assertEquals(500, focused.getHeight());
            assertEquals(before, page.locator("body").innerText());
            assertEquals(0, page.locator("[data-peegeeq-screenshot-mask]").count());
            assertEquals("password-canary", page.locator("input").inputValue());
            assertEquals("payload-canary", page.locator("textarea").first().inputValue());
            var nativeViewport = ImageIO.read(new ByteArrayInputStream(page.screenshot(
                    new Page.ScreenshotOptions().setAnimations(ScreenshotAnimations.DISABLED))));
            var nativeFocused = ImageIO.read(new ByteArrayInputStream(page.locator("main").screenshot(
                    new Locator.ScreenshotOptions().setAnimations(ScreenshotAnimations.DISABLED))));
            assertArrayEquals(pixels(nativeViewport), pixels(viewport),
                    "Viewport must reproduce native browser pixels with no screenshot overlays");
            assertArrayEquals(pixels(nativeFocused), pixels(focused),
                    "Focused capture must reproduce native browser pixels with no screenshot overlays");

            page.locator("#secret").evaluate("element => element.textContent = 'token-canary-BB'");
            page.locator("input").fill("different-password");
            page.locator("textarea").first().fill("different-payload");
            page.locator("pre").evaluate("element => element.textContent = 'different-reveal'");
            page.locator(".json-tree dd").evaluate("element => element.textContent = 'private-BB'");
            page.locator(".value-formatter code").evaluate("element => element.textContent = 'scalar-BB'");
            page.locator("button").focus();
            page.locator("button").evaluate("element => element.blur()");
            var changed = ManagementBrowserScreenshots.capture(page, page.locator("main"),
                    directory, "PW-TEST-001-02");
            var changedViewport = ImageIO.read(changed.getFirst().path().toFile());
            assertFalse(Arrays.equals(pixels(viewport), pixels(changedViewport)),
                    "Visible value changes must change captured pixels; inspect " + directory);
        }
    }

    @Test
    void waitsForClosingDialogBeforeSelectingFocusedEvidence() throws Exception {
        try (Fixture fixture = new Fixture("""
                    <main style="width:500px;height:300px">Completed result</main>
                    <div role="dialog" style="position:absolute;top:0;width:200px;height:100px">Closing</div>
                    <button onclick="const dialog=document.querySelector('[role=dialog]');
                      dialog.animate([{opacity:1},{opacity:0}],{duration:500}).finished.then(()=>dialog.remove())">
                      Complete</button>
                    """);
             BrowserContext context = ManagementBrowserPlaywrightSuite.browser().newContext(
                     new Browser.NewContextOptions().setViewportSize(1440, 900))) {
            var page = context.newPage();
            page.navigate(fixture.url());
            ManagementBrowserScreenshots.beginScenario("PW-TEST-003", directory);
            try {
                page.getByText("Complete", new com.microsoft.playwright.Page.GetByTextOptions().setExact(true)).click();
                ManagementBrowserScreenshots.captureCurrent(page);
                assertEquals(0, page.locator("[role=dialog]").count());
                var images = ManagementBrowserScreenshots.finishScenario();
                assertEquals(2, images.size());
                var focused = ImageIO.read(images.getLast().path().toFile());
                assertEquals(500, focused.getWidth());
                assertEquals(300, focused.getHeight());
            } finally {
                ManagementBrowserScreenshots.finishScenario();
            }
        }
    }

    @Test
    void artifactWriteFailureIsAnAcceptanceFailure() throws Exception {
        try (Fixture fixture = new Fixture("<main>Ready</main>");
             BrowserContext context = ManagementBrowserPlaywrightSuite.browser().newContext()) {
            var page = context.newPage();
            page.navigate(fixture.url());
            Path blocked = directory.resolve("not-a-directory");
            Files.writeString(blocked, "existing file");
            assertThrows(RuntimeException.class, () -> ManagementBrowserScreenshots.capture(
                    page, page.locator("main"), blocked, "PW-TEST-002-01"));
            assertEquals("existing file", Files.readString(blocked));
        }
    }

    private static int[] pixels(BufferedImage image) {
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }

    private static final class Fixture implements AutoCloseable {
        private final HttpServer server;

        Fixture(String html) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] body = html.getBytes(StandardCharsets.UTF_8);
            server.createContext("/", exchange -> {
                try (exchange) {
                    exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                }
            });
            server.start();
        }

        String url() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/"; }

        @Override public void close() { server.stop(0); }
    }
}
