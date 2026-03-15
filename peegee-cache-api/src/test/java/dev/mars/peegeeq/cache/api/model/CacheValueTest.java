package dev.mars.peegeeq.cache.api.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CacheValueTest {

    // --- Factory methods ---

    @Test
    void ofStringCreatesStringType() {
        CacheValue v = CacheValue.ofString("hello");
        assertEquals(ValueType.STRING, v.type());
        assertNotNull(v.binaryValue());
        assertNull(v.longValue());
        assertEquals("hello", v.asString());
    }

    @Test
    void ofBytesCreatesBytesType() {
        byte[] data = {1, 2, 3};
        CacheValue v = CacheValue.ofBytes(data);
        assertEquals(ValueType.BYTES, v.type());
        assertNotNull(v.binaryValue());
        assertNull(v.longValue());
    }

    @Test
    void ofJsonUtf8CreatesJsonType() {
        CacheValue v = CacheValue.ofJsonUtf8("{\"a\":1}");
        assertEquals(ValueType.JSON, v.type());
        assertNotNull(v.binaryValue());
        assertNull(v.longValue());
        assertEquals("{\"a\":1}", v.asString());
    }

    @Test
    void ofLongCreatesLongType() {
        CacheValue v = CacheValue.ofLong(42L);
        assertEquals(ValueType.LONG, v.type());
        assertNull(v.binaryValue());
        assertEquals(42L, v.longValue());
        assertEquals(42L, v.asLong());
    }

    // --- Compact constructor validation ---

    @Test
    void nullTypeThrowsNpe() {
        assertThrows(NullPointerException.class,
                () -> new CacheValue(null, null, null));
    }

    @Test
    void longTypeRequiresLongValue() {
        assertThrows(NullPointerException.class,
                () -> new CacheValue(ValueType.LONG, null, null));
    }

    @Test
    void longTypeRejectsBinaryValue() {
        assertThrows(IllegalArgumentException.class,
                () -> new CacheValue(ValueType.LONG, io.vertx.core.buffer.Buffer.buffer("x"), 1L));
    }

    @Test
    void binaryTypeRequiresBinaryValue() {
        assertThrows(NullPointerException.class,
                () -> new CacheValue(ValueType.STRING, null, null));
    }

    @Test
    void binaryTypeRejectsLongValue() {
        assertThrows(IllegalArgumentException.class,
                () -> new CacheValue(ValueType.STRING, io.vertx.core.buffer.Buffer.buffer("x"), 1L));
    }

    @Test
    void bytesTypeRequiresBinaryValue() {
        assertThrows(NullPointerException.class,
                () -> new CacheValue(ValueType.BYTES, null, null));
    }

    @Test
    void jsonTypeRequiresBinaryValue() {
        assertThrows(NullPointerException.class,
                () -> new CacheValue(ValueType.JSON, null, null));
    }

    // --- Accessor guard methods ---

    @Test
    void asStringOnLongValueThrows() {
        CacheValue v = CacheValue.ofLong(1L);
        assertThrows(IllegalStateException.class, v::asString);
    }

    @Test
    void asLongOnStringValueThrows() {
        CacheValue v = CacheValue.ofString("x");
        assertThrows(IllegalStateException.class, v::asLong);
    }
}
