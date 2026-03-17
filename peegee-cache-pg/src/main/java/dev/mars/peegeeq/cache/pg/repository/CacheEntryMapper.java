package dev.mars.peegeeq.cache.pg.repository;

import dev.mars.peegeeq.cache.api.model.CacheEntry;
import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.CacheValue;
import dev.mars.peegeeq.cache.api.model.ValueType;
import io.vertx.core.buffer.Buffer;
import io.vertx.sqlclient.Row;

import java.time.OffsetDateTime;

/**
 * Shared row-to-{@link CacheEntry} mapping for cache_entries queries.
 */
final class CacheEntryMapper {

    private CacheEntryMapper() {}

    static CacheEntry mapRow(Row row) {
        return mapRow(row, true);
    }

    static CacheEntry mapRow(Row row, boolean includeValue) {
        CacheKey key = new CacheKey(row.getString("namespace"), row.getString("cache_key"));

        CacheValue value = null;
        if (includeValue) {
            String valueType = row.getString("value_type");
            if ("LONG".equals(valueType)) {
                value = CacheValue.ofLong(row.getLong("numeric_value"));
            } else {
                Buffer buf = row.getBuffer("value_bytes");
                value = new CacheValue(ValueType.valueOf(valueType), buf, null);
            }
        }

        OffsetDateTime expiresAt = row.getOffsetDateTime("expires_at");
        OffsetDateTime createdAt = row.getOffsetDateTime("created_at");
        OffsetDateTime updatedAt = row.getOffsetDateTime("updated_at");
        OffsetDateTime lastAccessed = row.getOffsetDateTime("last_accessed_at");

        return new CacheEntry(
                key,
                value,
                row.getLong("version"),
                createdAt != null ? createdAt.toInstant() : null,
                updatedAt != null ? updatedAt.toInstant() : null,
                expiresAt != null ? expiresAt.toInstant() : null,
                row.getLong("hit_count"),
                lastAccessed != null ? lastAccessed.toInstant() : null
        );
    }
}
