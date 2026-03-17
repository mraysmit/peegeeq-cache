package dev.mars.peegeeq.cache.pg.repository;

import dev.mars.peegeeq.cache.api.model.PublishRequest;
import dev.mars.peegeeq.cache.pg.config.PgCacheStoreConfig;
import dev.mars.peegeeq.cache.pg.sql.PubSubSql;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Repository for pub/sub operations using PostgreSQL LISTEN/NOTIFY.
 * Handles publish via the shared connection pool.
 */
public final class PgPubSubRepository {

    private static final Logger log = LoggerFactory.getLogger(PgPubSubRepository.class);

    private final Pool pool;
    private final PubSubSql sql;
    private final int maxPayloadBytes;

    public PgPubSubRepository(Pool pool, PgCacheStoreConfig config) {
        this.pool = Objects.requireNonNull(pool, "pool");
        Objects.requireNonNull(config, "config");
        this.sql = PubSubSql.forPrefix(config.pubSubChannelPrefix());
        this.maxPayloadBytes = config.maxPayloadBytes();
    }

    /**
     * Publishes a notification to the specified channel.
     *
     * @return Future resolving to 1 on success
     */
    public Future<Integer> publish(PublishRequest request) {
        try {
            validate(request);
        } catch (IllegalArgumentException e) {
            return Future.failedFuture(e);
        }

        String qualifiedChannel = sql.qualifiedChannel(request.channel());
        log.debug("publish channel={} qualifiedChannel={} payloadLength={}",
                request.channel(), qualifiedChannel,
                request.payload() != null ? request.payload().length() : 0);

        return pool.preparedQuery(PubSubSql.NOTIFY)
                .execute(Tuple.of(qualifiedChannel, request.payload()))
                .map(rows -> 1);
    }

    PubSubSql sql() {
        return sql;
    }

    private void validate(PublishRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.channel() == null || request.channel().isBlank()) {
            throw new IllegalArgumentException("channel must not be null or blank");
        }
        if (request.payload() == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
        int payloadSize = request.payload().getBytes(StandardCharsets.UTF_8).length;
        if (payloadSize > maxPayloadBytes) {
            throw new IllegalArgumentException(
                    "payload size %d bytes exceeds maximum %d bytes".formatted(payloadSize, maxPayloadBytes));
        }
    }
}
