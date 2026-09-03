package dev.mars.peegeeq.cache.pg;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PubSubPayloadCodecTest {

    @Test
    void preservesPayloadAndContentTypeAcrossTheVersionedWireEnvelope() {
        var decoded = PubSubPayloadCodec.decode(PubSubPayloadCodec.encode(
                "line one\nline two 世界", "application/json; charset=utf-8"));

        assertEquals("line one\nline two 世界", decoded.payload());
        assertEquals("application/json; charset=utf-8", decoded.contentType());
    }

    @Test
    void preservesRawPostgresCompatibilityAndEscapesReservedPrefixCollisions() {
        var raw = PubSubPayloadCodec.decode(PubSubPayloadCodec.encode("ordinary", null));
        assertEquals("ordinary", raw.payload());
        assertNull(raw.contentType());

        String reserved = "__PGQ_CACHE_TYPED_V1__:customer-data";
        var escaped = PubSubPayloadCodec.decode(PubSubPayloadCodec.encode(reserved, null));
        assertEquals(reserved, escaped.payload());
        assertNull(escaped.contentType());

        var externalMalformed = PubSubPayloadCodec.decode("__PGQ_CACHE_TYPED_V1__:not-base64!");
        assertEquals("__PGQ_CACHE_TYPED_V1__:not-base64!", externalMalformed.payload());
        assertNull(externalMalformed.contentType());
    }
}
