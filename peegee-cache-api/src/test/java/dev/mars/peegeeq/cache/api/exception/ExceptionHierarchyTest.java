package dev.mars.peegeeq.cache.api.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the design contracts from Section 9.13:
 *
 * - CacheException is the base; catch(CacheException) catches all subtypes
 * - CacheKeyException covers key/namespace validation at the API boundary
 * - CacheStoreException wraps storage failures and always carries a cause
 * - LockNotHeldException signals non-owner or expired-lease operations
 */
class ExceptionHierarchyTest {

    // -- Hierarchy contract: catch(CacheException) catches everything --

    @Test
    void catchCacheExceptionCatchesAllSubtypes() {
        CacheException[] subtypes = {
                new CacheKeyException("bad key"),
                new CacheStoreException("db down", new RuntimeException("conn refused")),
                new LockNotHeldException("not owner")
        };
        for (CacheException ex : subtypes) {
            try {
                throw ex;
            } catch (CacheException caught) {
                assertSame(ex, caught);
            }
        }
    }

    // -- CacheException is unchecked --

    @Test
    void cacheExceptionIsUnchecked() {
        assertInstanceOf(RuntimeException.class, new CacheException("fail"));
    }

    @Test
    void cacheExceptionPreservesCause() {
        Throwable cause = new RuntimeException("root");
        CacheException ex = new CacheException("wrapped", cause);
        assertSame(cause, ex.getCause());
    }

    // -- CacheStoreException always carries a cause (design: "always carries a cause") --

    @Test
    void cacheStoreExceptionRejectsNullCause() {
        assertThrows(NullPointerException.class,
                () -> new CacheStoreException("no cause", null));
    }

    @Test
    void cacheStoreExceptionCarriesCause() {
        Throwable cause = new RuntimeException("pg error");
        CacheStoreException ex = new CacheStoreException("store failed", cause);
        assertSame(cause, ex.getCause());
    }

    // -- LockNotHeldException is distinct from generic CacheException --

    @Test
    void lockNotHeldExceptionIsCatchableSeparately() {
        LockNotHeldException ex = new LockNotHeldException("expired lease");
        try {
            throw ex;
        } catch (LockNotHeldException caught) {
            assertSame(ex, caught);
        }
    }
}
