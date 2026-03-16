package dev.mars.peegeeq.cache.examples;

import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;

final class ExampleLogSupport {

    private ExampleLogSupport() {
        throw new UnsupportedOperationException("Utility class");
    }

    static void logData(Logger log, String label, Object... keyValues) {
        log.info("{}: {}", label, orderedMap(keyValues));
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