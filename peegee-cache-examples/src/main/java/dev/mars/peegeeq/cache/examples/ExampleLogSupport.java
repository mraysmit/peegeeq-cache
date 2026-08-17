package dev.mars.peegeeq.cache.examples;

import org.slf4j.Logger;

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
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("Expected even number of key/value arguments");
        }

        var event = log.atInfo().addKeyValue("name", label);
        for (int index = 0; index < keyValues.length; index += 2) {
            event.addKeyValue(String.valueOf(keyValues[index]), keyValues[index + 1]);
        }
        event.log("example.data");
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
        log.atInfo()
                .addKeyValue("operation", label)
                .addKeyValue("duration.us", elapsedNanos / 1_000)
                .log("example.operation.completed");
    }
}
