package dev.mars.peegeeq.cache.benchmark;

import io.vertx.core.json.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/** Writes one self-contained, human-readable HTML benchmark evidence report. */
public final class BenchmarkEvidenceWriter {

    private BenchmarkEvidenceWriter() {
    }

    public static void write(Path reportFile, BenchmarkCaptureReport report,
                             BenchmarkEnvironment environment) throws IOException {
        Path target = reportFile.toAbsolutePath().normalize();
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temporary, html(report, environment), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String html(BenchmarkCaptureReport report, BenchmarkEnvironment environment) {
        StringBuilder out = new StringBuilder(64_000);
        out.append("""
                <!doctype html>
                <html lang="en"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>peegee-cache benchmark evidence</title>
                <style>
                :root{color-scheme:light dark;--bg:#f4f7fb;--panel:#fff;--text:#172033;--muted:#64748b;--line:#dbe3ef;--ok:#15803d;--bad:#b91c1c;--code:#111827;--codeText:#e5e7eb}
                *{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:14px/1.5 system-ui,-apple-system,"Segoe UI",sans-serif}main{max-width:1440px;margin:auto;padding:32px}
                header{padding:28px 32px;border-radius:18px;color:#fff;background:linear-gradient(135deg,#172554,#2563eb);box-shadow:0 14px 35px #17255433}h1{margin:4px 0 8px;font-size:clamp(28px,4vw,44px);line-height:1.1}h2{margin:0 0 18px;font-size:22px}h3{margin:0;font-size:18px}
                .eyebrow{margin:0;text-transform:uppercase;letter-spacing:.12em;font-weight:700;opacity:.8}.subtitle{margin:0;opacity:.85}section,article.run{margin-top:24px;padding:24px;background:var(--panel);border:1px solid var(--line);border-radius:14px;box-shadow:0 4px 16px #0f172a0b}
                .grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:12px}.card{padding:16px;border:1px solid var(--line);border-radius:10px}.label{display:block;color:var(--muted);font-size:12px;font-weight:700;text-transform:uppercase;letter-spacing:.06em}.value{display:block;margin-top:4px;font-size:20px;font-weight:750;overflow-wrap:anywhere}
                .status{display:inline-block;padding:4px 10px;border-radius:999px;font-weight:750;text-transform:uppercase}.status.passed,.status.running{color:var(--ok);background:#dcfce7}.status.failed,.status.rejected-dirty-working-tree{color:var(--bad);background:#fee2e2}
                .table-wrap{overflow:auto;border:1px solid var(--line);border-radius:10px}table{width:100%;border-collapse:collapse;font-variant-numeric:tabular-nums}th,td{padding:10px 12px;text-align:right;border-bottom:1px solid var(--line);white-space:nowrap}th{color:var(--muted);font-size:12px;text-transform:uppercase;letter-spacing:.04em}th:first-child,td:first-child{text-align:left}tr:last-child td{border-bottom:0}
                .run-head{display:flex;align-items:center;justify-content:space-between;gap:16px;margin-bottom:16px}.run-metrics{margin:16px 0}details{margin-top:16px;border:1px solid var(--line);border-radius:10px}summary{cursor:pointer;padding:12px 14px;font-weight:700}pre{margin:0;padding:16px;overflow:auto;max-height:520px;color:var(--codeText);background:var(--code);border-radius:0 0 10px 10px;font:12px/1.5 ui-monospace,Consolas,monospace;white-space:pre-wrap;overflow-wrap:anywhere}.failure{margin:14px 0;padding:12px;color:var(--bad);background:#fee2e2;border-radius:8px}footer{padding:28px 4px 8px;color:var(--muted);text-align:center}
                @media(prefers-color-scheme:dark){:root{--bg:#0b1220;--panel:#111827;--text:#e5e7eb;--muted:#94a3b8;--line:#334155}.status.passed,.status.running{background:#14532d;color:#bbf7d0}.status.failed,.status.rejected-dirty-working-tree,.failure{background:#7f1d1d;color:#fecaca}}
                @media print{body{background:#fff}main{max-width:none;padding:0}section,article.run,header{box-shadow:none;break-inside:avoid}details pre{max-height:none}}
                </style></head><body><main>
                """);
        header(out, report);
        overview(out, report);
        configuration(out, report, environment);
        aggregate(out, report);
        runs(out, report);
        environment(out, environment);
        return out.append("<footer>Self-contained peegee-cache benchmark evidence · no external assets</footer></main></body></html>\n").toString();
    }

    private static void header(StringBuilder out, BenchmarkCaptureReport report) {
        out.append("<header><p class=\"eyebrow\">peegee-cache performance evidence</p><h1>")
                .append(escape(report.benchmarkId())).append("</h1><p class=\"subtitle\">Generated ")
                .append(escape(report.completedAtUtc())).append(" · <span class=\"status ")
                .append(cssClass(report.status())).append("\">").append(escape(report.status()))
                .append("</span></p></header>");
    }

    private static void overview(StringBuilder out, BenchmarkCaptureReport report) {
        out.append("<section><h2>Benchmark overview</h2><div class=\"grid\">");
        card(out, "Status", report.status());
        card(out, "Runs", report.runs().size() + " / " + report.runsRequested());
        card(out, "Successful", report.successfulRuns());
        card(out, "Failed", report.runs().size() - report.successfulRuns());
        card(out, "Started UTC", report.startedAtUtc());
        card(out, "Elapsed", duration(Duration.between(report.startedAtUtc(), report.completedAtUtc())));
        out.append("</div></section>");
    }

    private static void configuration(StringBuilder out, BenchmarkCaptureReport report,
                                      BenchmarkEnvironment environment) {
        Map<?, ?> config = child(environment.values(), "benchmarkConfiguration");
        out.append("<section><h2>Configuration and acceptance gates</h2><div class=\"grid\">");
        card(out, "Repetitions", report.runsRequested());
        card(out, "Duration / workload", value(config, "durationSeconds", "unknown") + " s");
        card(out, "Concurrency", value(config, "concurrency", "unknown"));
        card(out, "Pool size", value(config, "poolSize", "unknown"));
        out.append("</div><div class=\"table-wrap\" style=\"margin-top:16px\"><table><thead><tr><th>Gate</th><th>Threshold</th></tr></thead><tbody>");
        gate(out, "Minimum throughput", value(config, "minimumThroughput", "unknown"), " operations/s");
        gate(out, "Maximum p99", value(config, "maximumP99Milliseconds", "unknown"), " ms");
        gate(out, "Maximum expiry lag", value(config, "maximumExpiryLagMilliseconds", "unknown"), " ms");
        gate(out, "Maximum failover recovery", value(config, "maximumFailoverRecoveryMilliseconds", "unknown"), " ms");
        gate(out, "Maximum telemetry throughput overhead", value(config, "maximumTelemetryOverheadPercent", "unknown"), "%");
        out.append("</tbody></table></div></section>");
    }

    private static void aggregate(StringBuilder out, BenchmarkCaptureReport report) {
        out.append("<section><h2>Aggregate results</h2><div class=\"table-wrap\"><table><thead><tr><th>Scenario</th><th>Runs</th><th>Total operations</th><th>Avg throughput/s</th><th>Min throughput/s</th><th>Avg p50 ms</th><th>Avg p95 ms</th><th>Avg p99 ms</th><th>Max p99 ms</th></tr></thead><tbody>");
        boolean present = false;
        for (String name : BenchmarkRunResult.REQUIRED_SCENARIOS) {
            List<BenchmarkScenarioResult> scenarios = scenarios(report, name);
            if (scenarios.isEmpty()) {
                continue;
            }
            present = true;
            out.append("<tr><td>").append(escape(name)).append("</td><td>").append(scenarios.size())
                    .append("</td><td>").append(scenarios.stream().mapToLong(BenchmarkScenarioResult::operations).sum())
                    .append("</td><td>").append(number(avg(scenarios, BenchmarkScenarioResult::throughputPerSecond), 2))
                    .append("</td><td>").append(number(scenarios.stream().mapToDouble(BenchmarkScenarioResult::throughputPerSecond).min().orElse(0), 2))
                    .append("</td><td>").append(number(avg(scenarios, BenchmarkScenarioResult::p50Milliseconds), 3))
                    .append("</td><td>").append(number(avg(scenarios, BenchmarkScenarioResult::p95Milliseconds), 3))
                    .append("</td><td>").append(number(avg(scenarios, BenchmarkScenarioResult::p99Milliseconds), 3))
                    .append("</td><td>").append(number(scenarios.stream().mapToDouble(BenchmarkScenarioResult::p99Milliseconds).max().orElse(0), 3))
                    .append("</td></tr>");
        }
        if (!present) {
            out.append("<tr><td colspan=\"9\">No completed benchmark results.</td></tr>");
        }
        out.append("</tbody></table></div></section>");
    }

    private static void runs(StringBuilder out, BenchmarkCaptureReport report) {
        out.append("<section><h2>Individual runs</h2>");
        if (report.runs().isEmpty()) {
            out.append("<p>No benchmark repetitions were executed.</p>");
        }
        for (CapturedBenchmarkRun run : report.runs()) {
            out.append("<article class=\"run\"><div class=\"run-head\"><div><h3>Run ").append(run.run())
                    .append("</h3><span class=\"label\">").append(escape(run.startedAtUtc())).append(" · ")
                    .append(number(run.elapsedSeconds(), 3)).append(" seconds</span></div><span class=\"status ")
                    .append(cssClass(run.status())).append("\">").append(escape(run.status())).append("</span></div>");
            if (run.failure() != null) {
                out.append("<div class=\"failure\"><strong>Failure:</strong> ").append(escape(run.failure())).append("</div>");
            }
            if (run.result() != null) {
                runResult(out, run.result());
            }
            out.append("<details><summary>Raw benchmark log</summary><pre>").append(escape(run.log()))
                    .append("</pre></details></article>");
        }
        out.append("</section>");
    }

    private static void runResult(StringBuilder out, BenchmarkRunResult result) {
        out.append("<div class=\"table-wrap\"><table><thead><tr><th>Scenario</th><th>Operations</th><th>Throughput/s</th><th>p50 ms</th><th>p95 ms</th><th>p99 ms</th></tr></thead><tbody>");
        for (BenchmarkScenarioResult scenario : result.scenarios()) {
            out.append("<tr><td>").append(escape(scenario.name())).append("</td><td>").append(scenario.operations())
                    .append("</td><td>").append(number(scenario.throughputPerSecond(), 2))
                    .append("</td><td>").append(number(scenario.p50Milliseconds(), 3))
                    .append("</td><td>").append(number(scenario.p95Milliseconds(), 3))
                    .append("</td><td>").append(number(scenario.p99Milliseconds(), 3)).append("</td></tr>");
        }
        out.append("</tbody></table></div><div class=\"grid run-metrics\">");
        card(out, "Telemetry throughput overhead", number(result.telemetry().throughputOverheadPercent(), 2) + "%");
        card(out, "Telemetry p99 overhead", number(result.telemetry().p99OverheadPercent(), 2) + "%");
        card(out, "Expiry lag", result.expiryLag().toMillis() + " ms");
        card(out, "Failover recovery", result.poolFailoverRecovery().toMillis() + " ms");
        out.append("</div>");
    }

    private static void environment(StringBuilder out, BenchmarkEnvironment environment) {
        Map<?, ?> host = child(environment.values(), "host");
        Map<?, ?> repository = child(environment.values(), "repository");
        Map<?, ?> toolchain = child(environment.values(), "toolchain");
        Map<?, ?> docker = child(environment.values(), "docker");
        out.append("<section><h2>Execution environment</h2><div class=\"grid\">");
        card(out, "Host", value(host, "name", "unknown"));
        card(out, "Operating system", value(host, "os", "unknown") + " " + value(host, "osVersion", ""));
        card(out, "CPU", value(host, "cpu", value(host, "architecture", "unknown")));
        card(out, "Processors visible to JVM", value(host, "processorsVisibleToJvm", value(host, "processors", "unknown")));
        card(out, "Physical memory", bytes(value(host, "physicalMemoryBytes", null)));
        card(out, "Java", value(toolchain, "javaVersion", "unknown") + " · " + value(toolchain, "javaVm", ""));
        card(out, "PostgreSQL image", value(docker, "postgresImage", "unknown"));
        card(out, "Git commit", value(repository, "commit", "unknown"));
        card(out, "Working tree clean", value(repository, "workingTreeClean", "unknown"));
        card(out, "Topology", value(host, "topology", "unspecified"));
        out.append("</div><details><summary>Complete captured environment (JSON)</summary><pre>")
                .append(escape(new JsonObject(environment.values()).encodePrettily()))
                .append("</pre></details></section>");
    }

    private static List<BenchmarkScenarioResult> scenarios(BenchmarkCaptureReport report, String name) {
        List<BenchmarkScenarioResult> results = new ArrayList<>();
        for (CapturedBenchmarkRun run : report.runs()) {
            if (run.result() != null) {
                run.result().scenarios().stream().filter(item -> item.name().equals(name))
                        .findFirst().ifPresent(results::add);
            }
        }
        return results;
    }

    private static double avg(List<BenchmarkScenarioResult> values,
                              ToDoubleFunction<BenchmarkScenarioResult> extractor) {
        return values.stream().mapToDouble(extractor).average().orElse(0);
    }

    private static void card(StringBuilder out, String label, Object value) {
        out.append("<div class=\"card\"><span class=\"label\">").append(escape(label))
                .append("</span><span class=\"value\">").append(escape(value)).append("</span></div>");
    }

    private static void gate(StringBuilder out, String label, Object value, String suffix) {
        out.append("<tr><td>").append(escape(label)).append("</td><td>")
                .append(escape(value)).append(escape(suffix)).append("</td></tr>");
    }

    private static String duration(Duration value) {
        long seconds = value.toSeconds();
        return "%dm %02ds".formatted(seconds / 60, seconds % 60);
    }

    private static String bytes(Object value) {
        return value instanceof Number number
                ? number(number.doubleValue() / (1024d * 1024d * 1024d), 2) + " GiB" : "unknown";
    }

    private static String number(double value, int decimals) {
        return String.format(Locale.ROOT, "%,." + decimals + "f", value);
    }

    private static String cssClass(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-");
    }

    private static String escape(Object value) {
        return value == null ? "" : value.toString().replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static Map<?, ?> child(Map<String, Object> root, String key) {
        Object value = root.get(key);
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private static Object value(Map<?, ?> map, String key, Object fallback) {
        Object value = map.get(key);
        return value == null ? fallback : value;
    }
}
