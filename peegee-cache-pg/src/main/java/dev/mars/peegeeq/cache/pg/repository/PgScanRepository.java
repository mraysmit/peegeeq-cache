package dev.mars.peegeeq.cache.pg.repository;

import dev.mars.peegeeq.cache.api.model.CacheEntry;
import dev.mars.peegeeq.cache.api.model.ScanRequest;
import dev.mars.peegeeq.cache.api.model.ScanResult;
import dev.mars.peegeeq.cache.pg.sql.CacheSql;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class PgScanRepository {

    private static final Logger log = LoggerFactory.getLogger(PgScanRepository.class);

    /** Upper bound on scan page size to prevent unbounded result sets. */
    public static final int MAX_LIMIT = 10_000;

    private final Pool pool;
    private final CacheSql sql;

    public PgScanRepository(Pool pool, String schemaName) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.sql = CacheSql.forSchema(schemaName);
    }

    public Future<ScanResult> scan(ScanRequest request) {
        log.debug("scan namespace={} prefix={} cursor={} limit={} includeValues={} includeExpired={}",
                request.namespace(), request.prefix(), request.cursor(),
                request.limit(), request.includeValues(), request.includeExpired());

        String query = request.includeExpired() ? sql.SCAN_INCLUDING_EXPIRED : sql.SCAN;
        // Fetch limit+1 to detect hasMore without an extra query
        int fetchLimit = request.limit() + 1;

        Tuple params = Tuple.of(
                request.namespace(),
                request.prefix(),
                request.cursor(),
                fetchLimit
        );

        return pool.preparedQuery(query)
                .execute(params)
                .map(rows -> {
                    List<CacheEntry> entries = new ArrayList<>();
                    for (Row row : rows) {
                    entries.add(CacheEntryMapper.mapRow(row, request.includeValues()));
                    }

                    boolean hasMore = entries.size() > request.limit();
                    if (hasMore) {
                        entries = entries.subList(0, request.limit());
                    }

                    String nextCursor = hasMore ? entries.get(entries.size() - 1).key().key() : null;

                    log.debug("scan namespace={} -> found={} hasMore={}",
                            request.namespace(), entries.size(), hasMore);

                    return new ScanResult(List.copyOf(entries), nextCursor, hasMore);
                });
    }
}
