package dev.mars.peegeeq.cache.rest.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Static source gate preventing browser tests from substituting product responses or markup. */
final class ManagementBrowserSourcePolicy {

    private static final Set<String> SOURCE_SUFFIXES = Set.of(
            ".java", ".js", ".mjs", ".cjs", ".ts", ".tsx");

    private static final List<ProhibitedPattern> PROHIBITED_PATTERNS = List.of(
            pattern("page.route", "\\bpage\\s*\\.\\s*route\\s*\\("),
            pattern("context.route", "\\bcontext\\s*\\.\\s*route\\s*\\("),
            pattern("route.fulfill", "\\broute\\s*\\.\\s*fulfill\\s*\\("),
            pattern("route.abort", "\\broute\\s*\\.\\s*abort\\s*\\("),
            pattern("route.continue", "\\broute\\s*\\.\\s*continue\\s*\\("),
            pattern("page.setContent", "\\bpage\\s*\\.\\s*setContent\\s*\\("),
            pattern("page.addInitScript", "\\bpage\\s*\\.\\s*addInitScript\\s*\\("),
            pattern("Playwright.create outside suite owner", "\\bPlaywright\\s*\\.\\s*create\\s*\\("),
            pattern("Chromium launch outside suite owner", "\\.\\s*chromium\\s*\\(\\s*\\)\\s*\\.\\s*launch\\s*\\("));

    private ManagementBrowserSourcePolicy() {
    }

    static List<String> violations(Path root) throws IOException {
        List<Path> sources;
        try (Stream<Path> paths = Files.walk(root)) {
            sources = paths
                    .filter(Files::isRegularFile)
                    .filter(ManagementBrowserSourcePolicy::isScannedSource)
                    .filter(path -> !path.getFileName().toString()
                            .equals("ManagementBrowserSourcePolicyTest.java"))
                    .sorted(Comparator.comparing(path -> normalizedRelativePath(root, path)))
                    .toList();
        }

        List<String> violations = new ArrayList<>();
        for (Path source : sources) {
            List<String> lines = Files.readAllLines(source);
            for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
                String line = lines.get(lineIndex);
                for (ProhibitedPattern prohibited : PROHIBITED_PATTERNS) {
                    if (prohibited.pattern().matcher(line).find()
                            && !isSuiteBrowserOwner(source, prohibited)) {
                        violations.add(normalizedRelativePath(root, source)
                                + ":" + (lineIndex + 1) + ": " + prohibited.label());
                    }
                }
            }
        }
        return List.copyOf(violations);
    }

    private static boolean isSuiteBrowserOwner(Path source, ProhibitedPattern prohibited) {
        if (!source.getFileName().toString().equals("ManagementBrowserPlaywrightSuite.java")) {
            return false;
        }
        return prohibited.label().equals("Playwright.create outside suite owner")
                || prohibited.label().equals("Chromium launch outside suite owner");
    }

    private static boolean isScannedSource(Path path) {
        String name = path.getFileName().toString();
        return SOURCE_SUFFIXES.stream().anyMatch(name::endsWith);
    }

    private static String normalizedRelativePath(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    private static ProhibitedPattern pattern(String label, String expression) {
        return new ProhibitedPattern(label, Pattern.compile(expression));
    }

    private record ProhibitedPattern(String label, Pattern pattern) {
    }
}
