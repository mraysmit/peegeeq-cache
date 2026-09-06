package dev.mars.peegeeq.cache.benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;

/**
 * Single-owner synchronous checkpoint writer: one authoritative JSON file per actual execution.
 * Use off event loops. Full snapshots are atomically replaced; no non-atomic replacement fallback.
 * This is a persistence boundary, not the bounded live interval recorder planned in B1.
 */
public final class BenchmarkRunJsonWriter {
    private final Path path;
    private BenchmarkRunEvidence previous;
    private String publishedJson;

    private BenchmarkRunJsonWriter(Path path) { this.path = path; }

    public static BenchmarkRunJsonWriter create(Path directory, BenchmarkRunEvidence initial) throws IOException {
        Objects.requireNonNull(initial, "initial");
        Path root = directory.toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path target = root.resolve(initial.executionId() + ".json");
        // Reserve identity exclusively; do not overwrite an existing execution or follow its symlink.
        Files.createFile(target);
        var writer = new BenchmarkRunJsonWriter(target);
        try {
            writer.checkpoint(initial);
            return writer;
        } catch (IOException | RuntimeException failure) {
            try { Files.delete(target); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    public Path path() { return path; }

    public synchronized void checkpoint(BenchmarkRunEvidence next) throws IOException {
        Objects.requireNonNull(next, "next");
        if (previous != null) {
            if (previous.status() != BenchmarkRunEvidence.Status.RUNNING) {
                throw new IllegalStateException("Finalised run cannot be checkpointed again");
            }
            var before = previous.toJson();
            var after = next.toJson();
            if (!previous.executionId().equals(next.executionId()) || !previous.startedAt().equals(next.startedAt())
                    || next.updatedAt().isBefore(previous.updatedAt()) || !previous.environment().equals(next.environment())
                    || !before.getJsonObject("plan").equals(after.getJsonObject("plan"))
                    || !before.getJsonObject("configuration").equals(after.getJsonObject("configuration"))
                    || !before.getJsonObject("timeline").equals(after.getJsonObject("timeline"))
                    || !extendsHistory(previous.measurements(), next.measurements())
                    || !extendsHistory(previous.diagnostics(), next.diagnostics())) {
                throw new IllegalArgumentException("Checkpoint changed run identity/configuration or rewrote history");
            }
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Owned evidence target is no longer a regular file: " + path);
        }
        if (publishedJson != null && !publishedJson.equals(Files.readString(path, StandardCharsets.UTF_8))) {
            throw new IOException("Evidence was modified outside this writer: " + path);
        }
        String json = next.toJson().encodePrettily() + "\n";
        Path temporary = Files.createTempFile(path.getParent(), path.getFileName() + "-", ".tmp");
        try {
            Files.writeString(temporary, json, StandardCharsets.UTF_8);
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            previous = next;
            publishedJson = json;
        } catch (IOException | RuntimeException failure) {
            try { Files.deleteIfExists(temporary); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private static <T> boolean extendsHistory(List<T> before, List<T> after) {
        return after.size() >= before.size() && after.subList(0, before.size()).equals(before);
    }
}
