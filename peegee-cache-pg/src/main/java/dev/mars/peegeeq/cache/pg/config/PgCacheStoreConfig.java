package dev.mars.peegeeq.cache.pg.config;

/**
 * PostgreSQL-specific store configuration.
 *
 * @param schemaName             schema name for all peegee-cache tables
 * @param pubSubChannelPrefix    prefix for LISTEN/NOTIFY channel names
 * @param maxPayloadBytes        maximum payload size in bytes for pub/sub publish (default 7500)
 */
public record PgCacheStoreConfig(
        String schemaName,
        String pubSubChannelPrefix,
        int maxPayloadBytes
) {

    public static final int DEFAULT_MAX_PAYLOAD_BYTES = 7500;

    public PgCacheStoreConfig {
        if (maxPayloadBytes <= 0) {
            throw new IllegalArgumentException("maxPayloadBytes must be > 0");
        }
    }

    public PgCacheStoreConfig(String schemaName, String pubSubChannelPrefix) {
        this(schemaName, pubSubChannelPrefix, DEFAULT_MAX_PAYLOAD_BYTES);
    }

    public static PgCacheStoreConfig defaults() {
        return new PgCacheStoreConfig("peegee_cache", "peegee_cache", DEFAULT_MAX_PAYLOAD_BYTES);
    }
}
