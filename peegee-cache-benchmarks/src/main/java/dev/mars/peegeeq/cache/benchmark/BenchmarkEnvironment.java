package dev.mars.peegeeq.cache.benchmark;

import com.sun.management.OperatingSystemMXBean;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Portable, best-effort host and toolchain metadata captured by Java. */
public record BenchmarkEnvironment(Map<String, Object> values) {

    public BenchmarkEnvironment {
        values = Map.copyOf(values);
    }

    static BenchmarkEnvironment capture(Path repositoryRoot, String topology, String postgresImage,
                                        BenchmarkConfig config) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("capturedAtUtc", Instant.now().toString());
        root.put("host", host(topology));
        root.put("toolchain", toolchain());
        root.put("repository", repository(repositoryRoot));
        root.put("docker", docker(repositoryRoot, postgresImage));
        root.put("benchmarkConfiguration", config.toMap());
        return new BenchmarkEnvironment(root);
    }

    boolean workingTreeClean() {
        Object repository = values.get("repository");
        if (!(repository instanceof Map<?, ?> repositoryMap)) {
            return false;
        }
        return Boolean.TRUE.equals(repositoryMap.get("workingTreeClean"));
    }

    private static Map<String, Object> host(String topology) {
        Runtime runtime = Runtime.getRuntime();
        Map<String, Object> host = new LinkedHashMap<>();
        host.put("name", hostName());
        host.put("topology", topology);
        host.put("os", System.getProperty("os.name"));
        host.put("osVersion", System.getProperty("os.version"));
        host.put("architecture", System.getProperty("os.arch"));
        host.put("processorsVisibleToJvm", runtime.availableProcessors());
        host.put("jvmMaximumMemoryBytes", runtime.maxMemory());
        host.put("cpu", cpuDescription());
        if (ManagementFactory.getOperatingSystemMXBean() instanceof OperatingSystemMXBean os) {
            host.put("physicalMemoryBytes", os.getTotalMemorySize());
            host.put("committedVirtualMemoryBytes", os.getCommittedVirtualMemorySize());
        }
        host.put("linuxMemInfo", readIfPresent(Path.of("/proc/meminfo"), 32_768));
        host.put("linuxOsRelease", readIfPresent(Path.of("/etc/os-release"), 16_384));
        host.put("disks", disks());
        return host;
    }

    private static Map<String, Object> toolchain() {
        Map<String, Object> toolchain = new LinkedHashMap<>();
        toolchain.put("javaVersion", System.getProperty("java.version"));
        toolchain.put("javaVendor", System.getProperty("java.vendor"));
        toolchain.put("javaVm", System.getProperty("java.vm.name"));
        toolchain.put("javaHome", System.getProperty("java.home"));
        toolchain.put("jvmArguments", ManagementFactory.getRuntimeMXBean().getInputArguments()
                .stream().map(BenchmarkEnvironment::scrub).toList());
        toolchain.put("maven", command(Path.of("."), "mvn", "--version"));
        return toolchain;
    }

    private static Map<String, Object> repository(Path root) {
        Map<String, Object> repository = new LinkedHashMap<>();
        repository.put("commit", commandText(root, "git", "rev-parse", "HEAD"));
        repository.put("branch", commandText(root, "git", "branch", "--show-current"));
        String status = commandText(root, "git", "status", "--porcelain=v1");
        repository.put("workingTreeClean", status != null && status.isBlank());
        repository.put("status", status);
        repository.put("remote", scrub(commandText(root, "git", "remote", "get-url", "origin")));
        return repository;
    }

    private static Map<String, Object> docker(Path root, String postgresImage) {
        Map<String, Object> docker = new LinkedHashMap<>();
        docker.put("version", command(root, "docker", "version", "--format", "{{json .}}"));
        docker.put("info", command(root, "docker", "info", "--format", "{{json .}}"));
        docker.put("postgresImage", postgresImage);
        docker.put("postgresImageIdentity", command(root, "docker", "image", "inspect", postgresImage));
        return docker;
    }

    private static List<Map<String, Object>> disks() {
        List<Map<String, Object>> disks = new ArrayList<>();
        for (FileStore store : FileSystems.getDefault().getFileStores()) {
            try {
                Map<String, Object> disk = new LinkedHashMap<>();
                disk.put("name", store.name());
                disk.put("type", store.type());
                disk.put("totalBytes", store.getTotalSpace());
                disk.put("usableBytes", store.getUsableSpace());
                disks.add(disk);
            } catch (IOException ignored) {
                // A transient or inaccessible mount must not prevent benchmark capture.
            }
        }
        return disks;
    }

    private static String cpuDescription() {
        String linuxCpuInfo = readIfPresent(Path.of("/proc/cpuinfo"), 256_000);
        if (linuxCpuInfo != null) {
            return linuxCpuInfo.lines()
                    .filter(line -> line.toLowerCase(Locale.ROOT).startsWith("model name"))
                    .map(line -> line.substring(line.indexOf(':') + 1).trim())
                    .findFirst().orElse(System.getProperty("os.arch"));
        }
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (osName.contains("windows")) {
            String registry = commandText(Path.of("."), "reg", "query",
                    "HKLM\\HARDWARE\\DESCRIPTION\\System\\CentralProcessor\\0",
                    "/v", "ProcessorNameString");
            if (registry != null) {
                String value = registry.lines().filter(line -> line.contains("ProcessorNameString"))
                        .map(line -> line.replaceFirst(".*REG_SZ\\s+", "").trim())
                        .findFirst().orElse(null);
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        if (osName.contains("mac")) {
            String value = commandText(Path.of("."), "sysctl", "-n", "machdep.cpu.brand_string");
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return System.getenv().getOrDefault("PROCESSOR_IDENTIFIER", System.getProperty("os.arch"));
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
            return System.getenv().getOrDefault("COMPUTERNAME", "unknown");
        }
    }

    private static String readIfPresent(Path path, int maximumBytes) {
        try {
            if (!Files.isRegularFile(path)) {
                return null;
            }
            byte[] bytes = Files.readAllBytes(path);
            return new String(bytes, 0, Math.min(bytes.length, maximumBytes), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            return null;
        }
    }

    private static String commandText(Path directory, String... command) {
        Map<String, Object> result = command(directory, command);
        return (String) result.get("output");
    }

    private static Map<String, Object> command(Path directory, String... command) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("command", List.of(command));
        try {
            Process process = new ProcessBuilder(command).directory(directory.toFile())
                    .redirectErrorStream(true).start();
            byte[] outputBytes;
            boolean completed;
            try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                var output = executor.submit(() -> process.getInputStream().readAllBytes());
                completed = process.waitFor(15, TimeUnit.SECONDS);
                if (!completed) {
                    process.destroyForcibly();
                    process.waitFor(5, TimeUnit.SECONDS);
                }
                outputBytes = output.get(5, TimeUnit.SECONDS);
            }
            String output = new String(outputBytes, StandardCharsets.UTF_8).trim();
            result.put("exitCode", completed ? process.exitValue() : -1);
            result.put("output", scrub(output));
        } catch (Exception failure) {
            result.put("exitCode", -1);
            result.put("output", failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
        return result;
    }

    private static String scrub(String value) {
        if (value == null) {
            return null;
        }
        return value
                .replaceAll("(?i)(password|passwd|token|secret|api[_-]?key)(\\s*=\\s*)\\S+", "$1$2***")
                .replaceAll("(?i)(https?://)[^/@\\s]+@", "$1***@");
    }
}
