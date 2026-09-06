package dev.mars.peegeeq.cache.rest.server;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Publishes verified captures in the sibling UI projects' flat documentation layout. */
public final class ManagementBrowserScreenshotGallery {
    private static final Pattern ROW = Pattern.compile(
            "<tr><td><code>(PW-[A-Z]+-[0-9]{3})</code><br>(.*?)</td><td>([A-Z_]+) / .*?</tr>", Pattern.DOTALL);
    private static final Pattern IMAGE = Pattern.compile(
            "<img [^>]*alt=\"[^\"]*-(\\d+)-(viewport|element)\\.png\" src=\"data:image/png;base64,([^\"]+)\"");

    private ManagementBrowserScreenshotGallery() { }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) throw new IllegalArgumentException("Usage: <verified-report.html> <documentation-screenshots-directory>");
        publish(Files.readString(Path.of(args[0])), Path.of(args[1]));
    }

    static void publish(String html, Path directory) throws IOException {
        var scenarios = new ArrayList<Scenario>();
        var rows = ROW.matcher(html);
        while (rows.find()) {
            String block = rows.group();
            if (!block.contains("class=\"status passed\"")) {
                throw new IllegalArgumentException("Documentation publication requires passing evidence");
            }
            var captures = new ArrayList<Capture>();
            var images = IMAGE.matcher(block);
            while (images.find()) {
                byte[] bytes = Base64.getDecoder().decode(images.group(3));
                var image = ImageIO.read(new ByteArrayInputStream(bytes));
                String kind = images.group(2);
                if (image == null || (kind.equals("viewport") && (image.getWidth() != 1440 || image.getHeight() != 900))) {
                    throw new IllegalArgumentException("Invalid screenshot in verified evidence");
                }
                captures.add(new Capture(images.group(1), kind, bytes));
            }
            if (captures.stream().noneMatch(c -> c.kind.equals("viewport"))
                    || captures.stream().noneMatch(c -> c.kind.equals("element"))) {
                throw new IllegalArgumentException("Missing screenshot pair in verified evidence");
            }
            String name = unescape(rows.group(2)).replaceFirst("^P\\d+ [^:]+ contract: ", "")
                    .replaceAll("([a-z])([A-Z])", "$1 $2");
            scenarios.add(new Scenario(rows.group(3), name, captures));
        }
        if (scenarios.isEmpty()) throw new IllegalArgumentException("No verified screenshot scenarios found");
        scenarios.sort(Comparator.comparing(Scenario::area).thenComparing(Scenario::name));
        Files.createDirectories(directory);
        var out = new StringBuilder("""
                <!doctype html><html lang="en"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>PeeGeeQ Cache screenshots</title><style>
                body{margin:0;background:#f4f7fb;color:#172033;font:16px/1.5 system-ui,sans-serif}
                main{max-width:1440px;margin:auto;padding:32px}h1{margin-bottom:8px}
                nav{display:flex;gap:12px;flex-wrap:wrap;margin:24px 0}a{color:#145dc2}
                article{background:white;border:1px solid #dbe3ef;border-radius:12px;padding:20px;margin:18px 0}
                .images{display:flex;flex-wrap:wrap;gap:20px}figure{margin:0;flex:1 1 420px;max-width:660px}
                img{width:100%;height:300px;object-fit:contain;object-position:top;background:#eef2f8;border:1px solid #dbe3ef}
                figcaption{overflow-wrap:anywhere;font-size:13px}input{font:inherit;padding:12px;width:min(700px,90%)}
                [hidden]{display:none!important}h2{padding-top:20px;border-top:1px solid #dbe3ef}
                </style></head><body><main><h1>PeeGeeQ Cache screenshots</h1>
                <p>Verified browser captures, arranged by feature and behavior. Click an image to open the full-size PNG.</p>
                """);
        out.append("<p>").append(scenarios.size()).append(" scenarios · ")
                .append(scenarios.stream().mapToInt(s -> s.captures.size()).sum()).append(" screenshots</p>");
        var timestamp = Pattern.compile("<time datetime=\"[^\"]+\">[^<]+</time>").matcher(html);
        if (timestamp.find()) out.append("<p>Source run: ").append(timestamp.group()).append("</p>");
        out.append("<label for=\"search\">Find a feature or behavior</label><br><input id=\"search\" type=\"search\" placeholder=\"e.g. JSON, lock, authentication\"><nav>");
        for (String area : scenarios.stream().map(Scenario::area).distinct().toList()) {
            out.append("<a href=\"#").append(slug(area)).append("\">").append(title(area)).append("</a>");
        }
        out.append("</nav>");
        var used = new HashMap<String, Integer>();
        String currentArea = "";
        for (var scenario : scenarios) {
            if (!scenario.area.equals(currentArea)) {
                currentArea = scenario.area;
                out.append("<h2 id=\"").append(slug(currentArea)).append("\">").append(title(currentArea)).append("</h2>");
            }
            String base = slug(scenario.area) + "-" + slug(scenario.name);
            int occurrence = used.merge(base, 1, Integer::sum);
            if (occurrence > 1) base += "-" + occurrence;
            out.append("<article data-search=\"").append(escape((scenario.area + " " + scenario.name).toLowerCase(Locale.ROOT)))
                    .append("\"><h3>").append(escape(scenario.name)).append("</h3><div class=\"images\">");
            for (var capture : scenario.captures) {
                String filename = base + (capture.sequence.equals("1") ? "" : "-capture-" + capture.sequence)
                        + "-" + capture.kind + ".png";
                writeIfChanged(directory.resolve(filename), capture.bytes);
                out.append("<figure><a href=\"").append(filename).append("\"><img loading=\"lazy\" src=\"")
                        .append(filename).append("\" alt=\"").append(escape(scenario.name + " — " + capture.kind))
                        .append("\"></a><figcaption>").append(filename).append("</figcaption></figure>");
            }
            out.append("</div></article>");
        }
        out.append("""
                <script>document.querySelector('#search').addEventListener('input',event=>{
                const query=event.target.value.toLowerCase();
                document.querySelectorAll('article').forEach(card=>card.hidden=!card.dataset.search.includes(query));
                });</script></main></body></html>
                """);
        writeIfChanged(directory.resolve("index.html"), out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void writeIfChanged(Path target, byte[] content) throws IOException {
        if (Files.exists(target) && Arrays.equals(Files.readAllBytes(target), content)) return;
        Files.write(target, content);
    }

    private static String slug(String value) {
        String slug = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        if (slug.length() > 130) slug = slug.substring(0, 130).replaceAll("-$", "");
        return slug.isEmpty() ? "screenshot" : slug;
    }

    private static String title(String area) {
        String text = area.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static String unescape(String text) {
        return text.replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private record Capture(String sequence, String kind, byte[] bytes) { }
    private record Scenario(String area, String name, List<Capture> captures) { }
}
