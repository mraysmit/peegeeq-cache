package dev.mars.peegeeq.cache.pg.service;

import dev.mars.peegeeq.cache.api.model.PublishRequest;
import dev.mars.peegeeq.cache.api.model.PubSubMessage;
import dev.mars.peegeeq.cache.api.pubsub.PubSubService;
import dev.mars.peegeeq.cache.api.pubsub.Subscription;
import dev.mars.peegeeq.cache.core.metrics.CacheMetrics;
import dev.mars.peegeeq.cache.core.telemetry.CacheOperation;
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
import java.time.Duration;
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
    private final CacheMetrics metrics;

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Consumer<PubSubMessage>>> handlers = new ConcurrentHashMap<>();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);

    private volatile PgConnection listenerConnection;
    private volatile long reconnectTimerId = -1L;

    public PgPubSubService(Vertx vertx, PgPubSubRepository repository,
                           PgConnectOptions connectOptions, PgCacheStoreConfig config) {
        this(vertx, repository, connectOptions, config, new CacheMetrics());
    }

    public PgPubSubService(Vertx vertx, PgPubSubRepository repository,
                           PgConnectOptions connectOptions, PgCacheStoreConfig config,
                           CacheMetrics metrics) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.connectOptions = Objects.requireNonNull(connectOptions, "connectOptions");
        this.sql = PubSubSql.forPrefix(Objects.requireNonNull(config, "config").pubSubChannelPrefix());
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /**
     * Opens the dedicated listener connection.
     */
    public Future<Void> start() {
        if (!started.compareAndSet(false, true)) {
            return Future.failedFuture(new IllegalStateException("PubSubService is already started"));
        }
        reconnectAttempts.set(0);
        return openListenerConnection()
                .onSuccess(v -> reconnectAttempts.set(0))
                .onFailure(err -> started.set(false));
    }

    /**
     * Closes the dedicated listener connection and clears all subscriptions.
     */
    public Future<Void> stop() {
        if (!started.compareAndSet(true, false)) {
            return Future.succeededFuture();
        }
        cancelPendingReconnect();
        reconnectAttempts.set(0);
        handlers.clear();
        metrics.recordActiveSubscriptions(0);
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
        return metrics.observe(CacheOperation.PUBSUB_PUBLISH, () -> {
            if (!started.get()) {
                return Future.failedFuture(new IllegalStateException("PubSubService is not started"));
            }
            return repository.publish(request).onSuccess(ignored -> metrics.recordPublish());
        });
    }

    @Override
    public Future<Subscription> subscribe(String channel, Consumer<PubSubMessage> handler) {
        return metrics.observe(CacheOperation.PUBSUB_SUBSCRIBE, () -> {
            if (!started.get()) {
                return Future.failedFuture(new IllegalStateException("PubSubService is not started"));
            }
            validateSubscribeArgs(channel, handler);
            PgConnection conn = listenerConnection;
            if (conn == null) {
                return Future.failedFuture(new IllegalStateException("Listener connection is not available"));
            }
            handlers.computeIfAbsent(channel, k -> new CopyOnWriteArrayList<>()).add(handler);
            return conn.query(sql.listen(channel)).execute()
                    .map(v -> (Subscription) new PgSubscription(channel, handler))
                    .onSuccess(ignored -> {
                        metrics.recordSubscribe();
                        metrics.recordActiveSubscriptions(handlerCount());
                    })
                    .onFailure(err -> removeHandler(channel, handler));
        });
    }

    // --- Internal ---

    private Future<Void> openListenerConnection() {
        return PgConnection.connect(vertx, connectOptions)
                .compose(conn -> {
                    if (!started.get()) {
                        return conn.close()
                                .compose(v -> Future.failedFuture(
                                        new IllegalStateException("PubSubService stopped while connecting")));
                    }
                    listenerConnection = conn;

                    conn.notificationHandler(notification ->
                            vertx.runOnContext(v -> handleNotification(notification.getChannel(), notification.getPayload()))
                    );

                    conn.closeHandler(v -> {
                        boolean wasCurrent = listenerConnection == conn;
                        if (wasCurrent) {
                            listenerConnection = null;
                        }
                        if (wasCurrent && started.get()) {
                            log.warn("Listener connection closed unexpectedly, scheduling reconnect");
                            scheduleReconnect();
                        }
                    });

                    log.info("Pub/sub listener connection established");
                    return Future.succeededFuture();
                });
    }

    private synchronized void scheduleReconnect() {
        if (!started.get() || reconnectTimerId >= 0) {
            return;
        }
        int attempt = reconnectAttempts.incrementAndGet();
        if (attempt > MAX_RECONNECT_ATTEMPTS) {
            log.error("Max reconnect attempts ({}) exceeded for pub/sub listener", MAX_RECONNECT_ATTEMPTS);
            return;
        }

        long delay = Math.min(BASE_RECONNECT_DELAY_MS * (1L << (attempt - 1)), MAX_RECONNECT_DELAY_MS);
        log.info("Scheduling pub/sub listener reconnect attempt {}/{} in {}ms", attempt, MAX_RECONNECT_ATTEMPTS, delay);

        reconnectTimerId = vertx.setTimer(delay, id -> {
            synchronized (PgPubSubService.this) {
                reconnectTimerId = -1L;
            }
            if (!started.get()) {
                return;
            }
            long reconnectStartedAt = System.nanoTime();
            openListenerConnection()
                        .compose(v -> replayListenChannels())
                        .onSuccess(v -> {
                            reconnectAttempts.set(0);
                            metrics.recordPubSubReconnect(attempt,
                                    Duration.ofNanos(System.nanoTime() - reconnectStartedAt), null);
                            log.info("Pub/sub listener reconnected and channels replayed (attempt {})", attempt);
                        })
                        .onFailure(err -> {
                            metrics.recordPubSubReconnect(attempt,
                                    Duration.ofNanos(System.nanoTime() - reconnectStartedAt), err);
                            log.warn("Reconnect attempt {} failed", attempt, err);
                            closeFailedReconnect().onComplete(v -> scheduleReconnect());
                        });
        });
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
        long dispatchStartedAt = System.nanoTime();
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
                metrics.recordNotificationDispatch(entry.getValue().size(),
                        Duration.ofNanos(System.nanoTime() - dispatchStartedAt));
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

    private synchronized void cancelPendingReconnect() {
        long timerId = reconnectTimerId;
        reconnectTimerId = -1L;
        if (timerId >= 0) {
            vertx.cancelTimer(timerId);
        }
    }

    private Future<Void> closeFailedReconnect() {
        PgConnection conn = listenerConnection;
        listenerConnection = null;
        return conn == null ? Future.succeededFuture() : conn.close().recover(err -> Future.succeededFuture());
    }

    private void removeHandler(String channel, Consumer<PubSubMessage> handler) {
        CopyOnWriteArrayList<Consumer<PubSubMessage>> channelHandlers = handlers.get(channel);
        if (channelHandlers == null) {
            return;
        }
        channelHandlers.remove(handler);
        if (channelHandlers.isEmpty()) {
            handlers.remove(channel, channelHandlers);
        }
        metrics.recordActiveSubscriptions(handlerCount());
    }

    private int handlerCount() {
        return handlers.values().stream().mapToInt(CopyOnWriteArrayList::size).sum();
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
            return metrics.observe(CacheOperation.PUBSUB_UNSUBSCRIBE, () -> {
                Future<Void> result = Future.succeededFuture();
                CopyOnWriteArrayList<Consumer<PubSubMessage>> channelHandlers = handlers.get(channel);
                if (channelHandlers != null) {
                    channelHandlers.remove(handler);
                    if (channelHandlers.isEmpty()) {
                        handlers.remove(channel, channelHandlers);
                        PgConnection conn = listenerConnection;
                        if (conn != null) {
                            result = conn.query(sql.unlisten(channel)).execute().mapEmpty();
                        }
                    }
                }
                metrics.recordActiveSubscriptions(handlerCount());
                return result;
            });
        }
    }
}
