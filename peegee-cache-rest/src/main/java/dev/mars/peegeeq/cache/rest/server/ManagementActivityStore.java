package dev.mars.peegeeq.cache.rest.server;

import dev.mars.peegeeq.cache.api.management.ManagementActivityEvent;
import dev.mars.peegeeq.cache.api.management.ManagementActivityPage;
import dev.mars.peegeeq.cache.api.management.ManagementActivityQuery;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Thread-safe bounded current-process activity view; durable audit remains authoritative. */
public final class ManagementActivityStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(ManagementActivityStore.class);

    private final int capacity;
    private final ArrayDeque<ManagementActivityEvent> events = new ArrayDeque<>();
    private final Set<String> retainedIds = new HashSet<>();
    private final Map<String, Consumer<ManagementActivityEvent>> listeners = new HashMap<>();

    public ManagementActivityStore(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    public void publish(ManagementActivityEvent event) {
        Objects.requireNonNull(event, "event");
        List<Consumer<ManagementActivityEvent>> recipients;
        synchronized (this) {
            if (!retainedIds.add(event.eventId())) {
                throw new IllegalArgumentException("eventId is already retained");
            }
            events.addFirst(event);
            while (events.size() > capacity) {
                retainedIds.remove(events.removeLast().eventId());
            }
            recipients = List.copyOf(listeners.values());
        }
        recipients.forEach(listener -> {
            try {
                listener.accept(event);
            } catch (RuntimeException failure) {
                LOGGER.warn("management.activity.listener_failed");
            }
        });
    }

    public synchronized AutoCloseable listen(Consumer<ManagementActivityEvent> listener) {
        Objects.requireNonNull(listener, "listener");
        String listenerId = UUID.randomUUID().toString();
        listeners.put(listenerId, listener);
        return () -> {
            synchronized (ManagementActivityStore.this) {
                listeners.remove(listenerId);
            }
        };
    }

    public synchronized ManagementActivityPage recent(
            String setupId,
            ManagementActivityQuery query) {
        Objects.requireNonNull(setupId, "setupId");
        Objects.requireNonNull(query, "query");
        boolean afterFound = query.after() == null;
        List<ManagementActivityEvent> matches = new ArrayList<>(query.limit() + 1);
        for (ManagementActivityEvent event : events) {
            if (!event.setupId().equals(setupId)) {
                continue;
            }
            if (!afterFound) {
                if (event.eventId().equals(query.after())) {
                    afterFound = true;
                }
                continue;
            }
            if (matches(event, query)) {
                matches.add(event);
                if (matches.size() > query.limit()) {
                    break;
                }
            }
        }
        if (!afterFound) {
            throw new IllegalArgumentException("after does not identify retained setup activity");
        }
        boolean hasMore = matches.size() > query.limit();
        if (hasMore) {
            matches.removeLast();
        }
        String nextAfter = hasMore ? matches.getLast().eventId() : null;
        return new ManagementActivityPage(matches, nextAfter, hasMore);
    }

    private static boolean matches(
            ManagementActivityEvent event,
            ManagementActivityQuery query) {
        return (query.namespace() == null || query.namespace().equals(event.namespace()))
                && (query.action() == null || query.action().equals(event.action()))
                && (query.outcome() == null || query.outcome() == event.outcome());
    }
}
