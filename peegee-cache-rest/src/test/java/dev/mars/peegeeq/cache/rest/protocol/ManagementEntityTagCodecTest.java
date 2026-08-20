package dev.mars.peegeeq.cache.rest.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ManagementEntityTagCodecTest {

    @Test
    void rendersAndParsesExactVersionTags() {
        assertEquals("\"v14\"", ManagementEntityTagCodec.render(14));
        assertEquals(14, ManagementEntityTagCodec.parseExact("\"v14\""));
        assertEquals(Long.MAX_VALUE,
                ManagementEntityTagCodec.parseExact("\"v9223372036854775807\""));
    }

    @Test
    void recognizesOnlyExplicitlyAllowedWildcardConditions() {
        assertEquals(ManagementPrecondition.requirePresent(),
                ManagementEntityTagCodec.parse("*", null, true, false));
        assertEquals(ManagementPrecondition.requireAbsent(),
                ManagementEntityTagCodec.parse(null, "*", false, true));

        assertThrows(IllegalArgumentException.class,
                () -> ManagementEntityTagCodec.parse("*", null, false, false));
        assertThrows(IllegalArgumentException.class,
                () -> ManagementEntityTagCodec.parse(null, "*", false, false));
    }

    @Test
    void rejectsMalformedAmbiguousOrOutOfRangeTags() {
        for (String invalid : new String[]{"W/\"v1\"", "\"v1\", \"v2\"", "v1", "\"v-1\"",
                "\"v9223372036854775808\"", "\"v01\"", "\"V1\"", "\"v\""}) {
            assertThrows(IllegalArgumentException.class,
                    () -> ManagementEntityTagCodec.parseExact(invalid), invalid);
        }
        assertThrows(IllegalArgumentException.class,
                () -> ManagementEntityTagCodec.parse("\"v1\"", "*", false, true));
    }

    @Test
    void missingRequiredIfMatchProducesTypedPreconditionFailure() {
        ManagementProtocolException exception = assertThrows(ManagementProtocolException.class,
                () -> ManagementEntityTagCodec.requireExactIfMatch(null));

        assertEquals("PRECONDITION_REQUIRED", exception.code());
        assertEquals(428, exception.status());
    }
}
