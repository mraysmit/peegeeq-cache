package dev.mars.peegeeq.cache.api.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CacheKeyTest {

    @Test
    void validKeyCreatesSuccessfully() {
        CacheKey key = new CacheKey("ns", "k");
        assertEquals("ns", key.namespace());
        assertEquals("k", key.key());
    }

    @Test
    void asQualifiedKeyReturnsNamespaceColonKey() {
        CacheKey key = new CacheKey("myns", "mykey");
        assertEquals("myns:mykey", key.asQualifiedKey());
    }

    @Test
    void nullNamespaceThrowsNpe() {
        assertThrows(NullPointerException.class, () -> new CacheKey(null, "k"));
    }

    @Test
    void nullKeyThrowsNpe() {
        assertThrows(NullPointerException.class, () -> new CacheKey("ns", null));
    }

    @Test
    void blankNamespaceThrowsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> new CacheKey("", "k"));
        assertThrows(IllegalArgumentException.class, () -> new CacheKey("   ", "k"));
    }

    @Test
    void blankKeyThrowsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> new CacheKey("ns", ""));
        assertThrows(IllegalArgumentException.class, () -> new CacheKey("ns", "   "));
    }

    @Test
    void equalityBasedOnNamespaceAndKey() {
        CacheKey a = new CacheKey("ns", "k");
        CacheKey b = new CacheKey("ns", "k");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void differentNamespaceOrKeyAreNotEqual() {
        assertNotEquals(new CacheKey("ns1", "k"), new CacheKey("ns2", "k"));
        assertNotEquals(new CacheKey("ns", "k1"), new CacheKey("ns", "k2"));
    }
}
