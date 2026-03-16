package dev.mars.peegeeq.cache.examples;

import dev.mars.peegeeq.cache.api.model.CacheEntry;
import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.CacheSetRequest;
import dev.mars.peegeeq.cache.api.model.CacheSetResult;
import dev.mars.peegeeq.cache.api.model.CacheValue;
import dev.mars.peegeeq.cache.api.model.SetMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Optional;

/**
 * Demonstrates a read-through caching flow with stampede-safe write behavior.
 */
public final class CacheReadThroughExample {

    private static final Logger log = LoggerFactory.getLogger(CacheReadThroughExample.class);

    private CacheReadThroughExample() {
    }

    public static void main(String[] args) throws Exception {
        ExampleRuntimeSupport.runWithDefaultManager(log, "CacheReadThroughExample", manager -> {
            var cache = manager.cache();

            CacheKey key = new CacheKey("products", "42");
            log.debug("Read-through lookup for {}", key.asQualifiedKey());
            Optional<CacheEntry> cached = ExampleLogSupport.timed(log, "cache.get(read-through)", () ->
                    ExampleRuntimeSupport.await(cache.cache().get(key)));
            if (cached.isPresent()) {
                ExampleLogSupport.logData(log, "cache-hit",
                        "key", key.asQualifiedKey(),
                        "value", cached.get().value().asString());
                return;
            }

            String loadedFromSource = loadProductJson("42");
                ExampleLogSupport.logData(log, "cache-miss source payload",
                    "key", key.asQualifiedKey(),
                    "value", loadedFromSource);
            CacheSetRequest writeIfAbsent = new CacheSetRequest(
                    key,
                    CacheValue.ofJsonUtf8(loadedFromSource),
                    Duration.ofMinutes(10),
                    SetMode.ONLY_IF_ABSENT,
                    null,
                    true
            );
                CacheSetResult setResult = ExampleLogSupport.timed(log, "cache.set(only-if-absent)", () ->
                    ExampleRuntimeSupport.await(cache.cache().set(writeIfAbsent)));

            if (setResult.applied()) {
                ExampleLogSupport.logData(log, "cache-fill winner",
                    "key", key.asQualifiedKey(),
                    "value", loadedFromSource,
                    "version", setResult.newVersion());
            } else {
                Optional<CacheEntry> existing = ExampleLogSupport.timed(log, "cache.get(lost-race)", () ->
                    ExampleRuntimeSupport.await(cache.cache().get(key)));
                ExampleLogSupport.logData(log, "cache-fill lost race",
                    "key", key.asQualifiedKey(),
                    "existing", existing.map(entry -> entry.value().asString()).orElse("<missing>"));
            }
        });
    }

    private static String loadProductJson(String productId) {
        return "{\"id\":\"" + productId + "\",\"name\":\"Widget\",\"price\":1299}";
    }
}
