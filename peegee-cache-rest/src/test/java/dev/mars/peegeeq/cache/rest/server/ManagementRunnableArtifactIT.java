package dev.mars.peegeeq.cache.rest.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagementRunnableArtifactIT {

    @TempDir
    Path temporaryDirectory;

    @Test
    void runnableJarHasOneLoggingProviderAndOnlyProductionResources() throws Exception {
        Path artifact = ManagementBrowserRunConfig.current().runnableArtifact();
        assertTrue(Files.isRegularFile(artifact), "runnable management artifact");
        try (JarFile jar = new JarFile(artifact.toFile())) {
            assertEquals(
                    ManagementServerMain.class.getName(),
                    jar.getManifest().getMainAttributes().getValue("Main-Class"));
            assertNotNull(jar.getEntry("openapi/peegeeq-cache-management-v1.yaml"));
            assertNotNull(jar.getEntry("ui/index.html"));
            assertNotNull(jar.getEntry(
                    "dev/mars/peegeeq/cache/rest/server/ManagementServerMain.class"));
            assertFalse(jar.stream().anyMatch(entry ->
                    entry.getName().startsWith("dev/mars/peegeeq/cache/test/")
                            || entry.getName().contains("ManagementBrowserHarness")));
            assertFalse(jar.stream().anyMatch(entry ->
                    entry.getName().contains("management-browser-harness")));

            var providerEntry = jar.getEntry(
                    "META-INF/services/org.slf4j.spi.SLF4JServiceProvider");
            assertNotNull(providerEntry, "SLF4J service provider descriptor");
            List<String> providers;
            try (var input = jar.getInputStream(providerEntry)) {
                providers = new String(input.readAllBytes(), StandardCharsets.UTF_8).lines()
                        .map(String::trim)
                        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                        .distinct()
                        .toList();
            }
            assertEquals(List.of("org.slf4j.simple.SimpleServiceProvider"), providers);

            try (DataInputStream input = new DataInputStream(jar.getInputStream(jar.getEntry(
                    "dev/mars/peegeeq/cache/rest/server/ManagementServerMain.class")))) {
                assertEquals(0xCAFEBABE, input.readInt());
                input.readUnsignedShort();
                assertEquals(65, input.readUnsignedShort(), "Java 21 class-file major version");
            }
        }
    }

    @Test
    void shadedArtifactStartsAndExportsReadinessAndPrometheusMetrics() throws Exception {
        Path artifact = ManagementBrowserRunConfig.current().runnableArtifact();
        int port = freePort();
        Path errorLog = temporaryDirectory.resolve("management-server.log");
        ProcessBuilder builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar",
                artifact.toString());
        builder.environment().put(
                "PEEGEEQ_MANAGEMENT_AUDIT_KEY", "0123456789abcdef0123456789abcdef");
        builder.environment().put("PEEGEEQ_MANAGEMENT_PORT", Integer.toString(port));
        builder.environment().put(
                "PEEGEEQ_MANAGEMENT_AUDIT_JOURNAL",
                temporaryDirectory.resolve("management-audit.jsonl").toString());
        builder.redirectError(errorLog.toFile());
        Process process = builder.start();
        try (BufferedReader output = process.inputReader(StandardCharsets.UTF_8)) {
            String tokenLine = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    return output.readLine();
                } catch (java.io.IOException failure) {
                    throw new java.io.UncheckedIOException(failure);
                }
            }).get(15, TimeUnit.SECONDS);
            assertNotNull(tokenLine, () -> "server exited early: " + read(errorLog));
            assertTrue(tokenLine.startsWith("PEEGEEQ_MANAGEMENT_BOOTSTRAP_TOKEN="));

            HttpResponse<String> readiness = awaitGet(port, "/health/ready");
            assertEquals(200, readiness.statusCode());
            HttpResponse<String> metrics = awaitGet(port, "/metrics");
            assertEquals(200, metrics.statusCode());
            assertTrue(metrics.body().contains("peegeeq_management_server_ready 1.0"));
        } finally {
            process.destroy();
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
        }
        String logs = read(errorLog);
        assertFalse(logs.contains("multiple SLF4J providers"), logs);
        assertFalse(logs.contains("No SLF4J providers were found"), logs);
    }

    private static HttpResponse<String> awaitGet(int port, String path) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        Throwable lastFailure = null;
        for (int attempt = 0; attempt < 40; attempt++) {
            try {
                return client.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create("http://127.0.0.1:" + port + path))
                                .timeout(Duration.ofSeconds(2))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
            } catch (java.io.IOException failure) {
                lastFailure = failure;
                Thread.sleep(100);
            }
        }
        throw new AssertionError("Packaged management server did not become reachable", lastFailure);
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static String read(Path path) {
        try {
            return Files.exists(path) ? Files.readString(path) : "";
        } catch (java.io.IOException failure) {
            return failure.toString();
        }
    }
}
