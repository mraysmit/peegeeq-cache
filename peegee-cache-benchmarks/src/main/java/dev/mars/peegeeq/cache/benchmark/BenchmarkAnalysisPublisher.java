package dev.mars.peegeeq.cache.benchmark;

import io.vertx.core.json.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Final-run analysis publisher. JSON remains authoritative; HTML is a self-contained derived view. */
public final class BenchmarkAnalysisPublisher {
    private BenchmarkAnalysisPublisher() { }

    public static void publish(Path jsonPath, Path htmlPath, BenchmarkAnalysisPolicy policy) throws IOException {
        Objects.requireNonNull(jsonPath, "jsonPath");
        Objects.requireNonNull(htmlPath, "htmlPath");
        var run = new JsonObject(Files.readString(jsonPath));
        if (!run.getJsonObject("execution", new JsonObject()).getBoolean("finalised", false)) {
            throw new IllegalArgumentException("Only finalised benchmark evidence can be analysed");
        }
        run.put("analysis", BenchmarkTrendAnalyzer.analyse(run, policy));
        byte[] json = (run.encodePrettily() + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
        replace(jsonPath, json);
        String digest = sha256(json);
        String id = escape(run.getString("executionId", "unknown"));
        String status = escape(run.getJsonObject("analysis").getString("status"));
        String embedded = escape(new String(json, StandardCharsets.UTF_8));
        String curves = curves(run);
        String html = "<!doctype html><html><head><meta charset=\"utf-8\"><title>Benchmark time-series report</title>"
                + "<style>body{font:15px system-ui;max-width:1100px;margin:auto;padding:2rem;color:#172033}"
                + "code,pre{background:#f3f5f8;padding:.2rem .4rem}pre{overflow:auto;white-space:pre-wrap}"
                + ".status{font-weight:700}svg{border:1px solid #ccd4df;width:100%;height:120px}</style></head><body>"
                + "<h1>Benchmark time-series report</h1><p>Execution <code>" + id + "</code></p>"
                + "<p class=\"status\">Analysis: " + status + "</p><p>Authoritative JSON SHA-256: <code>"
                + digest + "</code></p><h2>Time-series evidence</h2>" + curves
                + "<h2>Embedded authoritative result</h2><pre>" + embedded + "</pre></body></html>";
        replace(htmlPath, html.getBytes(StandardCharsets.UTF_8));
    }

    private static void replace(Path target, byte[] bytes) throws IOException {
        Path parent = target.toAbsolutePath().normalize().getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, target.getFileName() + "-", ".tmp");
        try {
            Files.write(temporary, bytes);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private record PlotPoint(String label, double value) { }

    private static String curves(JsonObject run) {
        var latency = new ArrayList<PlotPoint>();
        var throughput = new ArrayList<PlotPoint>();
        for (Object value : run.getJsonArray("measurements", new io.vertx.core.json.JsonArray())) {
            var measurement = (JsonObject) value;
            String label = measurement.getString("scenario", "unknown") + " / "
                    + measurement.getString("phase", "unknown") + " @ "
                    + measurement.getLong("startNanos", 0L) + " ns";
            var distributions = measurement.getJsonObject("latencyDistributions", new JsonObject());
            var series = distributions.getJsonObject("series", new JsonObject());
            var successful = series.getJsonObject("successfulService");
            if (successful != null && successful.getValue("p95UpperBoundNanos") != null) {
                latency.add(new PlotPoint(label, successful.getLong("p95UpperBoundNanos") / 1_000_000.0));
            }
            var rates = measurement.getJsonObject("rates");
            if (rates != null && rates.getValue("successfulPerSecond") != null) {
                throughput.add(new PlotPoint(label, rates.getDouble("successfulPerSecond")));
            }
        }
        return chart("p95-latency", "Successful service p95 (ms)", latency, "ms")
                + chart("successful-rate", "Successful completion rate", throughput, "requests/s");
    }

    private static String chart(String id, String title, List<PlotPoint> points, String unit) {
        if (points.isEmpty()) return "<section><h3>" + escape(title) + "</h3><p>Not available.</p></section>";
        double maximum = points.stream().mapToDouble(PlotPoint::value).max().orElse(1);
        if (maximum <= 0) maximum = 1;
        StringBuilder coordinates = new StringBuilder();
        StringBuilder markers = new StringBuilder();
        for (int index = 0; index < points.size(); index++) {
            double x = points.size() == 1 ? 550 : 20 + index * 1060.0 / (points.size() - 1);
            double y = 100 - points.get(index).value() * 80 / maximum;
            String pair = String.format(Locale.ROOT, "%.2f,%.2f", x, y);
            if (!coordinates.isEmpty()) coordinates.append(' ');
            coordinates.append(pair);
            markers.append("<circle cx=\"").append(String.format(Locale.ROOT, "%.2f", x))
                    .append("\" cy=\"").append(String.format(Locale.ROOT, "%.2f", y))
                    .append("\" r=\"3\"><title>").append(escape(points.get(index).label())).append(": ")
                    .append(String.format(Locale.ROOT, "%.3f", points.get(index).value())).append(' ')
                    .append(escape(unit)).append("</title></circle>");
        }
        return "<section><h3>" + escape(title) + "</h3><svg data-series=\"" + id
                + "\" role=\"img\" aria-label=\"" + escape(title) + "\"><line x1=\"20\" y1=\"100\" x2=\"1080\" y2=\"100\" stroke=\"#64748b\"/>"
                + "<polyline fill=\"none\" stroke=\"#2563eb\" stroke-width=\"2\" points=\""
                + coordinates + "\"/>" + markers + "</svg></section>";
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
