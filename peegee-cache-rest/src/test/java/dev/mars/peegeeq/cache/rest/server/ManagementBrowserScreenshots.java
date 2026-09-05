package dev.mars.peegeeq.cache.rest.server;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.ScreenshotAnimations;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Per-scenario visual evidence: native browser pixels, without masks or capture-time DOM changes. */
final class ManagementBrowserScreenshots {
    private static final ThreadLocal<Session> CURRENT = new ThreadLocal<>();
    private static String runId = UUID.randomUUID().toString();

    private ManagementBrowserScreenshots() { }

    static void startRun() {
        CURRENT.remove();
        runId = UUID.randomUUID().toString();
    }

    static void beginScenario(String id, Path artifacts) {
        CURRENT.set(new Session(id, artifacts.resolve("screenshots").resolve(runId).resolve(id)));
    }

    static List<ManagementBrowserEvidenceReport.Screenshot> finishScenario() {
        Session session = CURRENT.get();
        CURRENT.remove();
        return session == null ? List.of() : List.copyOf(session.images);
    }

    /** Declared after the real BrowserContext in try-with-resources so capture precedes closure. */
    static CaptureScope beforeClose(BrowserContext context) {
        context.onPage(page -> page.setViewportSize(1440, 900));
        return new CaptureScope(context, CURRENT.get());
    }

    static void captureCurrent(Page page) {
        Session session = CURRENT.get();
        if (session != null) session.capturePage(page, focusTarget(page));
    }

    /** Explicit checkpoints may supply the exact control or panel asserted by a scenario. */
    static void captureCurrent(Page page, Locator focus) {
        Session session = CURRENT.get();
        if (session != null) session.capturePage(page, focus);
    }

    private static Locator focusTarget(Page page) {
        // A success assertion may resolve while its dialog is still leaving. Select the target
        // only after finite UI motion settles; continuous spinners must not block evidence.
        page.waitForFunction("""
                () => document.getAnimations().every(animation =>
                  (!animation.pending && animation.playState !== 'running')
                  || !Number.isFinite(animation.effect?.getComputedTiming().endTime))
                """);
        for (String selector : List.of("[role=dialog]:visible", "[role=alert]:visible",
                "[role=region]:visible", "main:visible", "body")) {
            Locator target = page.locator(selector).last();
            if (target.count() > 0) return target;
        }
        throw new IllegalStateException("No visible screenshot target");
    }

    static List<ManagementBrowserEvidenceReport.Screenshot> capture(
            Page page, Locator focus, Path directory, String stem) {
        if (!stem.matches("PW-[A-Z]+-[0-9]{3}-[0-9]+")) {
            throw new IllegalArgumentException("Screenshot name must contain a scenario ID and sequence");
        }
        try {
            Files.createDirectories(directory);
            focus.scrollIntoViewIfNeeded();
            Path viewport = directory.resolve(stem + "-viewport.png");
            Path element = directory.resolve(stem + "-element.png");
            page.screenshot(new Page.ScreenshotOptions().setPath(viewport).setFullPage(false)
                    .setAnimations(ScreenshotAnimations.DISABLED));
            focus.screenshot(new Locator.ScreenshotOptions().setPath(element)
                    .setAnimations(ScreenshotAnimations.DISABLED));
            return List.of(new ManagementBrowserEvidenceReport.Screenshot("viewport", viewport),
                    new ManagementBrowserEvidenceReport.Screenshot("element", element));
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not save scenario screenshots", failure);
        }
    }

    static final class CaptureScope implements AutoCloseable {
        private final BrowserContext context;
        private final Session session;

        private CaptureScope(BrowserContext context, Session session) {
            this.context = context;
            this.session = session;
        }

        @Override public void close() {
            if (session == null) return;
            for (Page page : context.pages()) {
                if (!page.isClosed() && !session.captured.contains(page)) {
                    session.capturePage(page, focusTarget(page));
                }
            }
        }
    }

    private static final class Session {
        private final String id;
        private final Path directory;
        private final List<ManagementBrowserEvidenceReport.Screenshot> images = new ArrayList<>();
        private final Set<Page> captured = Collections.newSetFromMap(new IdentityHashMap<>());
        private int sequence;

        private Session(String id, Path directory) {
            this.id = id;
            this.directory = directory;
        }

        private void capturePage(Page page, Locator focus) {
            images.addAll(capture(page, focus, directory, id + "-" + (++sequence)));
            captured.add(page);
        }
    }
}
