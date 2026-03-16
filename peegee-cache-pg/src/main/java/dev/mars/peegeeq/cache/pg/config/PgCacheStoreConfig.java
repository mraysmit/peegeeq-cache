package dev.mars.peegeeq.cache.pg.config;

/**
 * PostgreSQL-specific store configuration.
 *
 * @param schemaName             schema name for all peegee-cache tables
 * @param pubSubChannelPrefix    prefix for LISTEN/NOTIFY channel names
 */
public record PgCacheStoreConfig(
        String schemaName,
        String pubSubChannelPrefix
) {

    public static PgCacheStoreConfig defaults() {
        return new PgCacheStoreConfig("peegee_cache", "peegee_cache");
    }
}
