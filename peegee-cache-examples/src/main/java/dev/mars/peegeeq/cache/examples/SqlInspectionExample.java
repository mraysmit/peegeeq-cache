package dev.mars.peegeeq.cache.examples;

import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.CacheSetRequest;
import dev.mars.peegeeq.cache.api.model.CacheValue;
import dev.mars.peegeeq.cache.api.model.SetMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/** Demonstrates the stable native-SQL read views intended for operators and non-Java clients. */
public final class SqlInspectionExample {

    private static final Logger log = LoggerFactory.getLogger(SqlInspectionExample.class);

    private SqlInspectionExample() {
    }

    public static void main(String[] args) throws Exception {
        ExampleRuntimeSupport.runWithDefaultManager(log, "SQL inspection example", manager -> {
            CacheKey key = new CacheKey("example", "visible-from-sql");
            ExampleRuntimeSupport.await(manager.cache().cache().set(new CacheSetRequest(
                    key, CacheValue.ofString("value"), Duration.ofMinutes(5),
                    SetMode.UPSERT, null, false)));

            var rows = ExampleRuntimeSupport.await(manager.pool().query("""
                    SELECT namespace, cache_key, value_type, version, expires_at
                    FROM peegee_cache.live_entries
                    WHERE namespace = 'example'
                    ORDER BY cache_key
                    """).execute());
            rows.forEach(row -> log.info(
                    "live entry namespace={} key={} type={} version={} expiresAt={}",
                    row.getString("namespace"), row.getString("cache_key"),
                    row.getString("value_type"), row.getLong("version"),
                    row.getOffsetDateTime("expires_at")));
        });
    }
}
