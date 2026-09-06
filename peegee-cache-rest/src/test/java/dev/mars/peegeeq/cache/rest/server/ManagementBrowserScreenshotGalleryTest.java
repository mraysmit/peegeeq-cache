package dev.mars.peegeeq.cache.rest.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static java.nio.file.StandardOpenOption.READ;

import static org.junit.jupiter.api.Assertions.*;

class ManagementBrowserScreenshotGalleryTest {
    @TempDir Path directory;

    @Test void publishesFlatDescriptiveFilesAndVisibleFeatureGallery() throws Exception {
        String html = row("PW-ENTRY-041", "P3 entry-inspection contract: default valid JSON to a structured tree", "ENTRY");
        ManagementBrowserScreenshotGallery.publish(html, directory);
        assertTrue(Files.exists(directory.resolve("entry-default-valid-json-to-a-structured-tree-viewport.png")));
        assertTrue(Files.exists(directory.resolve("entry-default-valid-json-to-a-structured-tree-element.png")));
        try (var paths = Files.list(directory)) {
            assertTrue(paths.noneMatch(path -> Files.isDirectory(path) || path.getFileName().toString().contains("PW-")));
        }
        String index = Files.readString(directory.resolve("index.html"));
        assertTrue(index.contains("<h2 id=\"entry\">Entry</h2>"));
        assertTrue(index.contains("default valid JSON to a structured tree"));
        assertTrue(index.contains("<img "));
        assertFalse(index.contains("<details"));
        assertFalse(index.contains("<table"));
        assertTrue(index.contains("1 scenarios"));
        assertTrue(index.contains("2 screenshots"));
    }

    @Test void retainsOriginalPngBytesAndDisambiguatesRepeatedNames() throws Exception {
        ManagementBrowserScreenshotGallery.publish(row("PW-ENTRY-001", "Entry lifecycle", "ENTRY")
                + row("PW-ENTRY-002", "Entry lifecycle", "ENTRY"), directory);
        assertArrayEquals(png(1440, 900), Files.readAllBytes(directory.resolve("entry-entry-lifecycle-viewport.png")));
        assertTrue(Files.exists(directory.resolve("entry-entry-lifecycle-2-viewport.png")));
    }

    @Test void doesNotRewriteAnUnchangedScreenshotThatIsOpenByAViewer() throws Exception {
        String html = row("PW-ENTRY-001", "Entry lifecycle", "ENTRY");
        ManagementBrowserScreenshotGallery.publish(html, directory);
        Path screenshot = directory.resolve("entry-entry-lifecycle-viewport.png");

        try (FileChannel channel = FileChannel.open(screenshot, READ)) {
            var viewedImage = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
            assertEquals((byte) 0x89, viewedImage.get(0));
            ManagementBrowserScreenshotGallery.publish(html, directory);
        }

        assertArrayEquals(png(1440, 900), Files.readAllBytes(screenshot));
    }

    @Test void escapesLabelsAndKeepsNamesInsideTheFlatDirectory() throws Exception {
        ManagementBrowserScreenshotGallery.publish(row("PW-ENTRY-001", "../&lt;script&gt; unsafe / title", "ENTRY"), directory);
        String index = Files.readString(directory.resolve("index.html"));
        assertTrue(index.contains("&lt;script&gt; unsafe / title"));
        try (var paths = Files.list(directory)) {
            assertTrue(paths.allMatch(path -> path.getParent().equals(directory)));
        }
    }

    @Test void rejectsMissingPairBeforePublishing() throws Exception {
        String html = row("PW-ENTRY-001", "Entry lifecycle", "ENTRY");
        html = html.replaceFirst("<figure>.*?</figure>", "");
        String incomplete = html;
        assertThrows(IllegalArgumentException.class, () -> ManagementBrowserScreenshotGallery.publish(incomplete, directory));
        assertFalse(Files.exists(directory.resolve("index.html")));
    }

    @Test void rejectsCorruptImagesAndEmptyReports() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> ManagementBrowserScreenshotGallery.publish("", directory));
        String corrupt = row("PW-ENTRY-001", "Entry lifecycle", "ENTRY")
                .replace(Base64.getEncoder().encodeToString(png(1440, 900)), "bm90IGEgcG5n");
        assertThrows(IllegalArgumentException.class, () -> ManagementBrowserScreenshotGallery.publish(corrupt, directory));
        assertFalse(Files.exists(directory.resolve("index.html")));
    }

    private String row(String id, String name, String area) throws Exception {
        return "<tr><td><code>" + id + "</code><br>" + name + "</td><td>" + area
                + " / HIGH</td><td><span class=\"status passed\">PASSED</span></td><td>"
                + image(id, "viewport", 1440, 900) + image(id, "element", 500, 300) + "</td></tr>";
    }

    private String image(String id, String kind, int width, int height) throws Exception {
        return "<figure><img loading=\"lazy\" alt=\"" + id + " — " + id + "-1-" + kind
                + ".png\" src=\"data:image/png;base64," + Base64.getEncoder().encodeToString(png(width, height))
                + "\"><figcaption>" + id + "-1-" + kind + ".png</figcaption></figure>";
    }

    private byte[] png(int width, int height) throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }
}
