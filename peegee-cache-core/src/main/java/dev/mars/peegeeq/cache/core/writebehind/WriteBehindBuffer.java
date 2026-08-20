package dev.mars.peegeeq.cache.core.writebehind;

import dev.mars.peegeeq.cache.api.model.CacheKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe, bounded, last-write-wins buffer keyed by cache key. */
public final class WriteBehindBuffer {

    private final int maxBufferSize;
    private final ConcurrentHashMap<CacheKey, PendingWrite> writes = new ConcurrentHashMap<>();
    private final Object mutationLock = new Object();

    public WriteBehindBuffer(int maxBufferSize) {
        if (maxBufferSize < 1) {
            throw new IllegalArgumentException("maxBufferSize must be > 0");
        }
        this.maxBufferSize = maxBufferSize;
    }

    public OfferResult offer(PendingWrite write) {
        Objects.requireNonNull(write, "write");
        synchronized (mutationLock) {
            if (!writes.containsKey(write.key()) && writes.size() >= maxBufferSize) {
                return OfferResult.overflowed();
            }
            writes.put(write.key(), write);
            return OfferResult.accepted(writes.size() >= maxBufferSize);
        }
    }

    public List<PendingWrite> drain() {
        return drain(Integer.MAX_VALUE);
    }

    public List<PendingWrite> drain(int maximumEntries) {
        if (maximumEntries < 1) {
            throw new IllegalArgumentException("maximumEntries must be > 0");
        }
        synchronized (mutationLock) {
            int expectedSize = Math.min(maximumEntries, writes.size());
            List<PendingWrite> drained = new ArrayList<>(expectedSize);
            for (PendingWrite write : writes.values()) {
                if (drained.size() == maximumEntries) {
                    break;
                }
                drained.add(write);
            }
            drained.forEach(write -> writes.remove(write.key(), write));
            return List.copyOf(drained);
        }
    }

    public int size() {
        return writes.size();
    }

    public boolean isEmpty() {
        return writes.isEmpty();
    }

    public record OfferResult(boolean overflow, boolean atCapacity) {

        private static final OfferResult ACCEPTED = new OfferResult(false, false);
        private static final OfferResult ACCEPTED_AT_CAPACITY = new OfferResult(false, true);
        private static final OfferResult OVERFLOWED = new OfferResult(true, true);

        private static OfferResult accepted(boolean atCapacity) {
            return atCapacity ? ACCEPTED_AT_CAPACITY : ACCEPTED;
        }

        private static OfferResult overflowed() {
            return OVERFLOWED;
        }
    }
}
