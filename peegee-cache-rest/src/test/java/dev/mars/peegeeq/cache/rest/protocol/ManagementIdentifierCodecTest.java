package dev.mars.peegeeq.cache.rest.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ManagementIdentifierCodecTest {

    @Test
    void arbitraryUtf8RoundTripsThroughCanonicalUnpaddedBase64Url() {
        String identifier = "客户/%+: key\t";

        String encoded = ManagementIdentifierCodec.encodeKey(identifier);

        assertEquals("5a6i5oi3LyUrOiBrZXkJ", encoded);
        assertEquals(identifier, ManagementIdentifierCodec.decodeKey(encoded));
    }

    @Test
    void rejectsMalformedNonCanonicalOrInvalidDecodedIdentifiers() {
        assertThrows(IllegalArgumentException.class,
                () -> ManagementIdentifierCodec.decodeKey("%%%"));
        assertThrows(IllegalArgumentException.class,
                () -> ManagementIdentifierCodec.decodeKey("YQ=="));
        assertThrows(IllegalArgumentException.class,
                () -> ManagementIdentifierCodec.decodeKey("_w"));
        assertThrows(IllegalArgumentException.class,
                () -> ManagementIdentifierCodec.decodeKey(""));
        assertThrows(IllegalArgumentException.class,
                () -> ManagementIdentifierCodec.decodeKey("AA"));
    }

    @Test
    void enforcesDecodedUtf8ByteLimitsWithoutTrimmingIdentifierData() {
        assertThrows(IllegalArgumentException.class,
                () -> ManagementIdentifierCodec.encodeNamespace("n".repeat(129)));
        assertThrows(IllegalArgumentException.class,
                () -> ManagementIdentifierCodec.encodeKey("界".repeat(342)));

        String data = " /%+:\t\n界 ";
        assertEquals(data, ManagementIdentifierCodec.decodeKey(ManagementIdentifierCodec.encodeKey(data)));
    }
}
