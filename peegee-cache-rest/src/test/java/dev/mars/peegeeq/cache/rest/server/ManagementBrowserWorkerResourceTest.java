package dev.mars.peegeeq.cache.rest.server;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ManagementBrowserWorkerResourceTest {

    @Test
    void startsOneResourceReusesItAndStopsItExactlyOnce() throws Exception {
        AtomicInteger creations = new AtomicInteger();
        FakeResource fake = new FakeResource();
        ManagementBrowserWorkerResource<FakeResource> worker = new ManagementBrowserWorkerResource<>(
                () -> {
                    creations.incrementAndGet();
                    return fake;
                },
                FakeResource::start,
                FakeResource::stop);

        assertThrows(IllegalStateException.class, worker::resource);
        worker.start();
        worker.start();

        assertSame(fake, worker.resource());
        assertEquals(1, creations.get());
        assertEquals(1, fake.starts);

        worker.close();
        worker.close();

        assertEquals(1, fake.stops);
        assertThrows(IllegalStateException.class, worker::resource);
    }

    private static final class FakeResource {
        private int starts;
        private int stops;

        void start() {
            starts++;
        }

        void stop() {
            stops++;
        }
    }
}
