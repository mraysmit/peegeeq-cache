package dev.mars.peegeeq.cache.rest.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Locale;

/** Writes one portable, self-contained HTML file for a complete Playwright run. */
final class ManagementBrowserEvidenceWriter {

    private static final DateTimeFormatter HUMAN_TIMESTAMP = DateTimeFormatter
            .ofPattern("dd MMM uuuu, HH:mm:ss 'UTC'", Locale.ROOT)
            .withZone(ZoneOffset.UTC);

    private ManagementBrowserEvidenceWriter() {
    }

    static void write(Path reportFile, ManagementBrowserEvidenceReport report) throws IOException {
        String html = html(report);
        for (String canary : report.sensitiveCanaries()) {
            if (!canary.isEmpty() && html.contains(canary)) {
                throw new IllegalStateException(
                        "Playwright evidence contains a registered sensitive canary");
            }
        }

        Path target = reportFile.toAbsolutePath().normalize();
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temporary, html, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String html(ManagementBrowserEvidenceReport report) {
        long passed = report.scenarios().stream()
                .filter(result -> result.status().equalsIgnoreCase("PASSED"))
                .count();
        long failed = report.scenarios().size() - passed;
        String overallStatus = failed == 0 ? "PASSED" : "FAILED";
        StringBuilder out = new StringBuilder(32_000);
        out.append("""
                <!doctype html>
                <html lang="en"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>PeeGeeQ Cache Playwright evidence</title>
                <style>
                :root{color-scheme:light dark;--bg:#f4f7fb;--panel:#fff;--text:#172033;--muted:#64748b;--line:#dbe3ef;--ok:#15803d;--bad:#b91c1c;--code:#111827}
                *{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:14px/1.5 system-ui,-apple-system,"Segoe UI",sans-serif}main{max-width:1500px;margin:auto;padding:32px}
                header{padding:28px 32px;border-radius:18px;color:#fff;background:linear-gradient(135deg,#172554,#2563eb);box-shadow:0 14px 35px #17255433;overflow:hidden}header h1{margin:4px 0 10px;font-size:clamp(27px,4vw,43px);line-height:1.1;overflow-wrap:anywhere}.eyebrow,.label{font-size:11px;font-weight:750;text-transform:uppercase;letter-spacing:.08em}.eyebrow{margin:0;opacity:.8}.header-grid,.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(190px,1fr));gap:14px}.header-grid{margin-top:20px}.header-grid time,.header-grid code{display:block;color:#fff;font:600 14px/1.45 ui-monospace,Consolas,monospace;overflow-wrap:anywhere}.header-grid .label{display:block;color:#bfdbfe}
                section{margin-top:24px;padding:24px;background:var(--panel);border:1px solid var(--line);border-radius:14px;box-shadow:0 4px 16px #0f172a0b}h2{margin:0 0 18px}.card{padding:15px;border:1px solid var(--line);border-radius:10px}.card .label{display:block;color:var(--muted)}.value{display:block;margin-top:4px;font-size:18px;font-weight:750;overflow-wrap:anywhere}.status{display:inline-block;padding:4px 10px;border-radius:999px;font-weight:750}.passed{color:var(--ok);background:#dcfce7}.failed{color:var(--bad);background:#fee2e2}
                .table-wrap{overflow:auto;border:1px solid var(--line);border-radius:10px}table{width:100%;border-collapse:collapse;font-variant-numeric:tabular-nums}th,td{padding:10px 12px;text-align:left;vertical-align:top;border-bottom:1px solid var(--line)}th{color:var(--muted);font-size:11px;text-transform:uppercase;letter-spacing:.04em;white-space:nowrap}tr:last-child td{border-bottom:0}code{font:12px/1.45 ui-monospace,Consolas,monospace}.failure{color:var(--bad);font-weight:650;overflow-wrap:anywhere}footer{padding:28px 4px 8px;color:var(--muted);text-align:center}
                @media(prefers-color-scheme:dark){:root{--bg:#0b1220;--panel:#111827;--text:#e5e7eb;--muted:#94a3b8;--line:#334155}.passed{background:#14532d;color:#bbf7d0}.failed{background:#7f1d1d;color:#fecaca}}
                @media(max-width:640px){main{padding:16px}header{padding:22px 20px}section{padding:18px}.header-grid{grid-template-columns:1fr}}
                @media print{body{background:#fff}main{max-width:none;padding:0}section,header{box-shadow:none}}
                </style></head><body><main>
                """);
        out.append("<header><p class=\"eyebrow\">Real packaged-product browser assurance</p>")
                .append("<h1>PeeGeeQ Cache Playwright evidence</h1><span class=\"status ")
                .append(cssClass(overallStatus)).append("\">").append(overallStatus)
                .append("</span><div class=\"header-grid\">");
        headerItem(out, "Generated", time(report.completedAtUtc()));
        headerItem(out, "Started", time(report.startedAtUtc()));
        headerItem(out, "Report ID", "<code>" + escape(report.reportId()) + "</code>");
        out.append("</div></header>");

        out.append("<section><h2>Run summary</h2><div class=\"grid\">");
        card(out, "Scenarios", report.scenarios().size());
        card(out, "Passed", passed);
        card(out, "Failed", failed);
        card(out, "Elapsed", duration(Duration.between(report.startedAtUtc(), report.completedAtUtc())));
        out.append("</div></section>");

        environment(out, report.environment());
        scenarios(out, report);
        out.append("<footer>Self-contained PeeGeeQ Cache Playwright evidence · no external assets</footer>")
                .append("</main></body></html>\n");
        return out.toString();
    }

    private static void environment(StringBuilder out, ManagementBrowserEvidenceReport.Environment environment) {
        out.append("<section><h2>Execution environment</h2><div class=\"grid\">");
        card(out, "Java", environment.javaVersion());
        card(out, "Operating system", environment.operatingSystem());
        card(out, "CPU", environment.cpu());
        card(out, "Memory", environment.memory());
        card(out, "Chromium", environment.chromiumVersion());
        card(out, "PostgreSQL", environment.postgresVersion());
        card(out, "Git commit", environment.gitCommit());
        out.append("</div></section>");
    }

    private static void scenarios(StringBuilder out, ManagementBrowserEvidenceReport report) {
        out.append("<section><h2>Scenario evidence</h2><div class=\"table-wrap\"><table>")
                .append("<thead><tr><th>ID and behavior</th><th>Area / risk</th><th>Status</th>")
                .append("<th>Duration</th><th>Requirement</th><th>Observed operations</th>")
                .append("<th>Evidence</th><th>Failure</th></tr></thead><tbody>");
        report.scenarios().stream()
                .sorted(Comparator.comparing(ManagementBrowserEvidenceReport.ScenarioResult::id))
                .forEach(result -> {
                    out.append("<tr><td><code>").append(escape(result.id())).append("</code><br>")
                            .append(escape(result.name())).append("</td><td>")
                            .append(escape(result.area())).append(" / ").append(escape(result.risk()))
                            .append("</td><td><span class=\"status ").append(cssClass(result.status()))
                            .append("\">").append(escape(result.status())).append("</span></td><td>")
                            .append(result.durationMilliseconds()).append(" ms</td><td>")
                            .append(escape(result.requirement())).append("</td><td>")
                            .append(escape(String.join(", ", result.observedOperations()))).append("</td><td>")
                            .append(escape(result.evidence().stream().map(Enum::name).sorted().toList()))
                            .append("</td><td class=\"failure\">").append(escape(result.failure()))
                            .append("</td></tr>");
                });
        out.append("</tbody></table></div></section>");
    }

    private static void headerItem(StringBuilder out, String label, String htmlValue) {
        out.append("<div><span class=\"label\">").append(escape(label)).append("</span>")
                .append(htmlValue).append("</div>");
    }

    private static void card(StringBuilder out, String label, Object value) {
        out.append("<div class=\"card\"><span class=\"label\">").append(escape(label))
                .append("</span><span class=\"value\">").append(escape(value)).append("</span></div>");
    }

    private static String time(Instant instant) {
        return "<time datetime=\"" + escape(instant) + "\">"
                + escape(HUMAN_TIMESTAMP.format(instant)) + "</time>";
    }

    private static String duration(Duration duration) {
        long seconds = duration.toSeconds();
        return "%dm %02ds".formatted(seconds / 60, seconds % 60);
    }

    private static String cssClass(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-");
    }

    private static String escape(Object value) {
        return value == null ? "" : value.toString().replace("&", "&amp;")
                .replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
