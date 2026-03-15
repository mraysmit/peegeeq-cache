package dev.mars.peegeeq.cache.api.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LockKeyTest {

    @Test
    void validKeyCreatesSuccessfully() {
        LockKey key = new LockKey("ns", "lk");
        assertEquals("ns", key.namespace());
        assertEquals("lk", key.key());
    }

    @Test
    void asQualifiedKeyReturnsNamespaceColonKey() {
        LockKey key = new LockKey("locks", "resource-1");
        assertEquals("locks:resource-1", key.asQualifiedKey());
    }

    @Test
    void nullNamespaceThrowsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> new LockKey(null, "k"));
    }

    @Test
    void nullKeyThrowsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> new LockKey("ns", null));
    }

    @Test
    void blankNamespaceThrowsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> new LockKey("", "k"));
        assertThrows(IllegalArgumentException.class, () -> new LockKey("   ", "k"));
    }

    @Test
    void blankKeyThrowsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> new LockKey("ns", ""));
        assertThrows(IllegalArgumentException.class, () -> new LockKey("ns", "   "));
    }

    @Test
    void equalityBasedOnNamespaceAndKey() {
        LockKey a = new LockKey("ns", "k");
        LockKey b = new LockKey("ns", "k");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
