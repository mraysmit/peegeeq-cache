package dev.mars.peegeeq.cache.examples;

import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;

final class ExampleLogSupport {

    @FunctionalInterface
    interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    @FunctionalInterface
    interface ThrowingRunnable {
        void run() throws Exception;
    }

    private ExampleLogSupport() {
        throw new UnsupportedOperationException("Utility class");
    }

    static void logData(Logger log, String label, Object... keyValues) {
        log.info("{}: {}", label, orderedMap(keyValues));
    }

    static <T> T timed(Logger log, String label, ThrowingSupplier<T> supplier) throws Exception {
        long start = System.nanoTime();
        try {
            return supplier.get();
        } finally {
            logDuration(log, label, start);
        }
    }

    static void timed(Logger log, String label, ThrowingRunnable runnable) throws Exception {
        long start = System.nanoTime();
        try {
            runnable.run();
        } finally {
            logDuration(log, label, start);
        }
    }

    private static void logDuration(Logger log, String label, long startNanos) {
        long elapsedNanos = System.nanoTime() - startNanos;
        double elapsedMillis = elapsedNanos / 1_000_000.0;
        long elapsedMicros = elapsedNanos / 1_000;
        log.info("timer {}: {} ms ({} us)", label, String.format("%.3f", elapsedMillis), elapsedMicros);
    }

    private static Map<String, Object> orderedMap(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("Expected even number of key/value arguments");
        }

        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return map;
    }
}