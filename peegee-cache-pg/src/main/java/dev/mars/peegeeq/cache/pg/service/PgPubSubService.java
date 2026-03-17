package dev.mars.peegeeq.cache.pg.service;

import dev.mars.peegeeq.cache.api.model.PublishRequest;
import dev.mars.peegeeq.cache.api.model.PubSubMessage;
import dev.mars.peegeeq.cache.api.pubsub.PubSubService;
import dev.mars.peegeeq.cache.api.pubsub.Subscription;
import dev.mars.peegeeq.cache.pg.config.PgCacheStoreConfig;
import dev.mars.peegeeq.cache.pg.repository.PgPubSubRepository;
import dev.mars.peegeeq.cache.pg.sql.PubSubSql;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * PostgreSQL-backed pub/sub service using LISTEN/NOTIFY.
 * <p>
 * Publishing uses the shared connection pool. Subscribing uses a dedicated
 * non-pooled {@link PgConnection} for LISTEN, with automatic reconnection
 * on connection loss.
 */
public final class PgPubSubService implements PubSubService {

    private static final Logger log = LoggerFactory.getLogger(PgPubSubService.class);

    private static final long BASE_RECONNECT_DELAY_MS = 1_000;
    private static final long MAX_RECONNECT_DELAY_MS = 32_000;
    private static final int MAX_RECONNECT_ATTEMPTS = 5;

    private final Vertx vertx;
    private final PgPubSubRepository repository;
    private final PgConnectOptions connectOptions;
    private final PubSubSql sql;

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Consumer<PubSubMessage>>> handlers = new ConcurrentHashMap<>();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);

    private volatile PgConnection listenerConnection;

    public PgPubSubService(Vertx vertx, PgPubSubRepository repository,
                           PgConnectOptions connectOptions, PgCacheStoreConfig config) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.connectOptions = Objects.requireNonNull(connectOptions, "connectOptions");
        this.sql = PubSubSql.forPrefix(Objects.requireNonNull(config, "config").pubSubChannelPrefix());
    }

    /**
     * Opens the dedicated listener connection.
     */
    public Future<Void> start() {
        if (!started.compareAndSet(false, true)) {
            return Future.failedFuture(new IllegalStateException("PubSubService is already started"));
        }
        return openListenerConnection();
    }

    /**
     * Closes the dedicated listener connection and clears all subscriptions.
     */
    public Future<Void> stop() {
        if (!started.compareAndSet(true, false)) {
            return Future.succeededFuture();
        }
        handlers.clear();
        PgConnection conn = listenerConnection;
        listenerConnection = null;
        if (conn != null) {
            return conn.query(PubSubSql.unlistenAll()).execute()
                    .compose(v -> conn.close())
                    .recover(err -> {
                        log.warn("Error during listener connection cleanup", err);
                        return conn.close().recover(closeErr -> Future.succeededFuture());
                    });
        }
        return Future.succeededFuture();
    }

    public boolean isListenerConnected() {
        return started.get() && listenerConnection != null;
    }

    @Override
    public Future<Integer> publish(PublishRequest request) {
        if (!started.get()) {
            return Future.failedFuture(new IllegalStateException("PubSubService is not started"));
        }
        return repository.publish(request);
    }

    @Override
    public Future<Subscription> subscribe(String channel, Consumer<PubSubMessage> handler) {
        if (!started.get()) {
            return Future.failedFuture(new IllegalStateException("PubSubService is not started"));
        }
        try {
            validateSubscribeArgs(channel, handler);
        } catch (IllegalArgumentException e) {
            return Future.failedFuture(e);
        }

        handlers.computeIfAbsent(channel, k -> new CopyOnWriteArrayList<>()).add(handler);

        String qualifiedChannel = sql.qualifiedChannel(channel);
        PgConnection conn = listenerConnection;
        if (conn == null) {
            return Future.failedFuture(new IllegalStateException("Listener connection is not available"));
        }

        return conn.query(sql.listen(channel)).execute()
                .map(v -> new PgSubscription(channel, handler));
    }

    // --- Internal ---

    private Future<Void> openListenerConnection() {
        return PgConnection.connect(vertx, connectOptions)
                .map(conn -> {
                    listenerConnection = conn;
                    reconnectAttempts.set(0);

                    conn.notificationHandler(notification ->
                            vertx.runOnContext(v -> handleNotification(notification.getChannel(), notification.getPayload()))
                    );

                    conn.closeHandler(v -> {
                        listenerConnection = null;
                        if (started.get()) {
                            log.warn("Listener connection closed unexpectedly, scheduling reconnect");
                            scheduleReconnect();
                        }
                    });

                    log.info("Pub/sub listener connection established");
                    return (Void) null;
                });
    }

    private void scheduleReconnect() {
        int attempt = reconnectAttempts.incrementAndGet();
        if (attempt > MAX_RECONNECT_ATTEMPTS) {
            log.error("Max reconnect attempts ({}) exceeded for pub/sub listener", MAX_RECONNECT_ATTEMPTS);
            return;
        }

        long delay = Math.min(BASE_RECONNECT_DELAY_MS * (1L << (attempt - 1)), MAX_RECONNECT_DELAY_MS);
        log.info("Scheduling pub/sub listener reconnect attempt {}/{} in {}ms", attempt, MAX_RECONNECT_ATTEMPTS, delay);

        vertx.setTimer(delay, id ->
                openListenerConnection()
                        .compose(v -> replayListenChannels())
                        .onSuccess(v -> log.info("Pub/sub listener reconnected and channels replayed (attempt {})", attempt))
                        .onFailure(err -> {
                            log.warn("Reconnect attempt {} failed", attempt, err);
                            if (started.get()) {
                                scheduleReconnect();
                            }
                        })
        );
    }

    private Future<Void> replayListenChannels() {
        PgConnection conn = listenerConnection;
        if (conn == null) {
            return Future.failedFuture(new IllegalStateException("No connection for LISTEN replay"));
        }

        Set<String> channels = handlers.keySet();
        if (channels.isEmpty()) {
            return Future.succeededFuture();
        }

        Future<Void> chain = Future.succeededFuture();
        for (String channel : channels) {
            chain = chain.compose(v -> conn.query(sql.listen(channel)).execute().mapEmpty());
        }
        return chain;
    }

    private void handleNotification(String qualifiedChannel, String payload) {
        // Reverse-map the qualified channel to the raw channel name
        for (var entry : handlers.entrySet()) {
            String rawChannel = entry.getKey();
            if (sql.qualifiedChannel(rawChannel).equals(qualifiedChannel)) {
                PubSubMessage message = new PubSubMessage(
                        rawChannel,
                        payload,
                        null, // contentType is not carried in NOTIFY payload
                        System.currentTimeMillis()
                );
                for (Consumer<PubSubMessage> handler : entry.getValue()) {
                    try {
                        handler.accept(message);
                    } catch (Exception e) {
                        log.warn("Exception in pub/sub handler for channel '{}'", rawChannel, e);
                    }
                }
                return;
            }
        }
        log.debug("Received notification for unregistered channel: {}", qualifiedChannel);
    }

    private void validateSubscribeArgs(String channel, Consumer<PubSubMessage> handler) {
        if (channel == null || channel.isBlank()) {
            throw new IllegalArgumentException("channel must not be null or blank");
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler must not be null");
        }
    }

    /**
     * Subscription implementation that removes the handler on unsubscribe.
     */
    private final class PgSubscription implements Subscription {

        private final String channel;
        private final Consumer<PubSubMessage> handler;

        PgSubscription(String channel, Consumer<PubSubMessage> handler) {
            this.channel = channel;
            this.handler = handler;
        }

        @Override
        public String channel() {
            return channel;
        }

        @Override
        public Future<Void> unsubscribe() {
            CopyOnWriteArrayList<Consumer<PubSubMessage>> channelHandlers = handlers.get(channel);
            if (channelHandlers != null) {
                channelHandlers.remove(handler);
                if (channelHandlers.isEmpty()) {
                    handlers.remove(channel, channelHandlers);
                    PgConnection conn = listenerConnection;
                    if (conn != null) {
                        return conn.query(sql.unlisten(channel)).execute().mapEmpty();
                    }
                }
            }
            return Future.succeededFuture();
        }
    }
}
