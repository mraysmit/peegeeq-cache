package dev.mars.peegeeq.cache.api;

import dev.mars.peegeeq.cache.api.cache.CacheService;
import dev.mars.peegeeq.cache.api.counter.CounterService;
import dev.mars.peegeeq.cache.api.lock.LockService;
import dev.mars.peegeeq.cache.api.pubsub.PubSubService;
import dev.mars.peegeeq.cache.api.scan.ScanService;

public interface PeeGeeCache {

    CacheService cache();

    CounterService counters();

    LockService locks();

    ScanService scan();

    PubSubService pubSub();
}
