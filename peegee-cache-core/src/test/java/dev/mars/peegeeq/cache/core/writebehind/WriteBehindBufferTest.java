package dev.mars.peegeeq.cache.core.writebehind;

import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.CacheSetRequest;
import dev.mars.peegeeq.cache.api.model.CacheValue;
import dev.mars.peegeeq.cache.api.model.SetMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WriteBehindBufferTest {

    @Test
    void setThenSetCoalescesToLatestWrite() {
        WriteBehindBuffer buffer = new WriteBehindBuffer(10);
        CacheKey key = new CacheKey("coalesce", "set-set");

        buffer.offer(PendingWrite.set(request(key, "first"), 10));
        buffer.offer(PendingWrite.set(request(key, "second"), 20));

        List<PendingWrite> drained = buffer.drain();
        assertEquals(1, drained.size());
        assertEquals(PendingWrite.Operation.SET, drained.getFirst().operation());
        assertEquals(CacheValue.ofString("second"), drained.getFirst().request().value());
        assertEquals(20, drained.getFirst().acceptedAtNanos());
    }

    @Test
    void setThenDeleteCoalescesToDeleteMarker() {
        WriteBehindBuffer buffer = new WriteBehindBuffer(10);
        CacheKey key = new CacheKey("coalesce", "set-delete");

        buffer.offer(PendingWrite.set(request(key, "value"), 10));
        buffer.offer(PendingWrite.delete(key, 20));

        PendingWrite write = buffer.drain().getFirst();
        assertEquals(PendingWrite.Operation.DELETE, write.operation());
        assertEquals(key, write.key());
        assertEquals(null, write.request());
    }

    @Test
    void deleteThenSetCoalescesToSet() {
        WriteBehindBuffer buffer = new WriteBehindBuffer(10);
        CacheKey key = new CacheKey("coalesce", "delete-set");

        buffer.offer(PendingWrite.delete(key, 10));
        buffer.offer(PendingWrite.set(request(key, "replacement"), 20));

        PendingWrite write = buffer.drain().getFirst();
        assertEquals(PendingWrite.Operation.SET, write.operation());
        assertEquals(CacheValue.ofString("replacement"), write.request().value());
    }

    @Test
    void newKeyOverflowsAtCapacityButExistingKeyCanStillCoalesce() {
        WriteBehindBuffer buffer = new WriteBehindBuffer(1);
        CacheKey first = new CacheKey("capacity", "first");
        CacheKey second = new CacheKey("capacity", "second");

        assertFalse(buffer.offer(PendingWrite.set(request(first, "one"), 10)).overflow());
        assertTrue(buffer.offer(PendingWrite.set(request(second, "two"), 20)).overflow());
        assertFalse(buffer.offer(PendingWrite.delete(first, 30)).overflow());

        assertEquals(1, buffer.size());
        assertEquals(PendingWrite.Operation.DELETE, buffer.drain().getFirst().operation());
    }

    @Test
    void drainReturnsAllEntriesAndEmptiesBuffer() {
        WriteBehindBuffer buffer = new WriteBehindBuffer(3);
        buffer.offer(PendingWrite.set(request(new CacheKey("drain", "one"), "one"), 10));
        buffer.offer(PendingWrite.delete(new CacheKey("drain", "two"), 20));

        assertEquals(2, buffer.drain().size());
        assertEquals(0, buffer.size());
        assertTrue(buffer.isEmpty());
        assertTrue(buffer.drain().isEmpty());
    }

    @Test
    void concurrentWritesToSameKeyRemainOneValidPendingWrite() throws InterruptedException {
        WriteBehindBuffer buffer = new WriteBehindBuffer(10);
        CacheKey key = new CacheKey("concurrent", "shared");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Thread first = writer(buffer, request(key, "first"), ready, start);
        Thread second = writer(buffer, request(key, "second"), ready, start);
        ready.await();
        start.countDown();
        first.join();
        second.join();

        assertEquals(1, buffer.size());
        PendingWrite write = buffer.drain().getFirst();
        assertEquals(PendingWrite.Operation.SET, write.operation());
        assertTrue(List.of(CacheValue.ofString("first"), CacheValue.ofString("second"))
                .contains(write.request().value()));
    }

    private static Thread writer(
            WriteBehindBuffer buffer,
            CacheSetRequest request,
            CountDownLatch ready,
            CountDownLatch start) {
        return Thread.ofPlatform().start(() -> {
            ready.countDown();
            try {
                start.await();
                for (int i = 0; i < 1_000; i++) {
                    buffer.offer(PendingWrite.set(request, System.nanoTime()));
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private static CacheSetRequest request(CacheKey key, String value) {
        return new CacheSetRequest(
                key, CacheValue.ofString(value), null, SetMode.UPSERT, null, false);
    }
}
