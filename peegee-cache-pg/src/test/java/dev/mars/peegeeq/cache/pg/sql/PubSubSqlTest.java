package dev.mars.peegeeq.cache.pg.sql;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PubSubSqlTest {

    @Test
    void listenAndUnlistenEscapeEmbeddedIdentifierQuotes() {
        PubSubSql sql = PubSubSql.forPrefix("cache");

        assertEquals("LISTEN \"cache__orders\"\"priority\"", sql.listen("orders\"priority"));
        assertEquals("UNLISTEN \"cache__orders\"\"priority\"", sql.unlisten("orders\"priority"));
    }

    @Test
    void rejectsNulInPrefixOrChannel() {
        assertThrows(IllegalArgumentException.class, () -> PubSubSql.forPrefix("cache\0unsafe"));

        PubSubSql sql = PubSubSql.forPrefix("cache");
        assertThrows(IllegalArgumentException.class, () -> sql.qualifiedChannel("orders\0unsafe"));
    }

    @Test
    void rejectsQualifiedChannelLongerThanPostgresqlIdentifierLimitInUtf8Bytes() {
        PubSubSql sql = PubSubSql.forPrefix("p");
        String exactlySixtyThreeBytes = "a".repeat(60);
        String sixtyFourUtf8Bytes = "a".repeat(59) + "é";

        assertEquals(63, sql.qualifiedChannel(exactlySixtyThreeBytes).getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> sql.qualifiedChannel(sixtyFourUtf8Bytes));
        assertTrue(failure.getMessage().contains("63 UTF-8 bytes"));
    }

    @Test
    void rejectsNullOrBlankPrefixAndChannel() {
        assertThrows(NullPointerException.class, () -> PubSubSql.forPrefix(null));
        assertThrows(IllegalArgumentException.class, () -> PubSubSql.forPrefix("  "));

        PubSubSql sql = PubSubSql.forPrefix("cache");
        assertThrows(NullPointerException.class, () -> sql.qualifiedChannel(null));
        assertThrows(IllegalArgumentException.class, () -> sql.qualifiedChannel("  "));
    }
}
