package dev.mars.peegeeq.cache.rest.security;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalTokenSessionManagerTest {

    @Test
    void consumes256BitBootstrapTokenOnceAndCreatesHardenedCookie() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-22T12:00:00Z"));
        LocalTokenBootstrap bootstrap = LocalTokenSessionManager.start(
                LocalTokenAuthenticationConfig.defaults(), clock, new SequenceEntropy());
        String token = bootstrap.token();

        assertEquals(43, token.length());
        assertNoRetainedRawSecret(bootstrap.manager(), token);

        LocalManagementSession session = bootstrap.manager().exchange(
                InetAddress.getLoopbackAddress(), token, true);

        assertEquals("local-operator", session.identity().actor());
        assertEquals(java.util.Set.of("operator", "viewer"), session.identity().roles());
        assertTrue(session.cookie().httpOnly());
        assertTrue(session.cookie().secure());
        assertEquals("Strict", session.cookie().sameSite());
        assertEquals("/", session.cookie().path());
        assertEquals("PGQMGMTSESSION", session.cookie().name());
        ManagementAuthenticationException replay = assertThrows(
                ManagementAuthenticationException.class,
                () -> bootstrap.manager().exchange(InetAddress.getLoopbackAddress(), token, true));
        assertEquals("INVALID_BOOTSTRAP_TOKEN", replay.code());
    }

    @Test
    void rejectsNonLoopbackWrongTokenAndNonLocalMode() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-22T12:00:00Z"));
        LocalTokenBootstrap bootstrap = LocalTokenSessionManager.start(
                LocalTokenAuthenticationConfig.defaults(), clock, new SequenceEntropy());

        assertEquals("INVALID_BOOTSTRAP_TOKEN", assertThrows(
                ManagementAuthenticationException.class,
                () -> bootstrap.manager().exchange(
                        InetAddress.getByName("10.0.0.7"), bootstrap.token(), false)).code());
        assertEquals("INVALID_BOOTSTRAP_TOKEN", assertThrows(
                ManagementAuthenticationException.class,
                () -> bootstrap.manager().exchange(
                        InetAddress.getLoopbackAddress(), "not-the-token", false)).code());
        assertThrows(IllegalArgumentException.class, () -> new LocalTokenAuthenticationConfig(
                ManagementAuthenticationMode.TRUSTED_PROXY, Duration.ofMinutes(30), Duration.ofHours(8)));
    }

    @Test
    void enforcesIdleAndAbsoluteExpiryAndRotatesOnRegeneration() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-22T12:00:00Z"));
        LocalTokenBootstrap bootstrap = LocalTokenSessionManager.start(
                new LocalTokenAuthenticationConfig(
                        ManagementAuthenticationMode.LOCAL_TOKEN,
                        Duration.ofMinutes(30),
                        Duration.ofHours(2)),
                clock,
                new SequenceEntropy());
        LocalManagementSession original = bootstrap.manager().exchange(
                InetAddress.getLoopbackAddress(), bootstrap.token(), false);

        clock.advance(Duration.ofMinutes(29));
        LocalManagementSession refreshed = bootstrap.manager().authenticate(original.cookie().value());
        assertEquals(original.cookie().value(), refreshed.cookie().value());
        clock.advance(Duration.ofMinutes(31));
        assertEquals("SESSION_EXPIRED", assertThrows(
                ManagementAuthenticationException.class,
                () -> bootstrap.manager().authenticate(original.cookie().value())).code());

        LocalTokenBootstrap regenerated = bootstrap.manager().regenerateBootstrapToken();
        LocalManagementSession replacement = regenerated.manager().exchange(
                InetAddress.getLoopbackAddress(), regenerated.token(), false);
        assertNotEquals(original.cookie().value(), replacement.cookie().value());
        assertThrows(ManagementAuthenticationException.class,
                () -> bootstrap.manager().authenticate(original.cookie().value()));

        for (int index = 0; index < 4; index++) {
            clock.advance(Duration.ofMinutes(29));
            bootstrap.manager().authenticate(replacement.cookie().value());
        }
        clock.advance(Duration.ofMinutes(3).plusSeconds(59));
        bootstrap.manager().authenticate(replacement.cookie().value());
        clock.advance(Duration.ofSeconds(2));
        assertEquals("SESSION_EXPIRED", assertThrows(
                ManagementAuthenticationException.class,
                () -> bootstrap.manager().authenticate(replacement.cookie().value())).code());
    }

    @Test
    void logoutAndShutdownEraseSessionAndRejectFurtherWork() throws Exception {
        LocalTokenBootstrap bootstrap = LocalTokenSessionManager.start(
                LocalTokenAuthenticationConfig.defaults(),
                Clock.fixed(Instant.parse("2026-08-22T12:00:00Z"), ZoneOffset.UTC),
                new SequenceEntropy());
        LocalManagementSession session = bootstrap.manager().exchange(
                InetAddress.getLoopbackAddress(), bootstrap.token(), false);

        bootstrap.manager().logout(session.cookie().value());
        assertThrows(ManagementAuthenticationException.class,
                () -> bootstrap.manager().authenticate(session.cookie().value()));

        LocalTokenBootstrap regenerated = bootstrap.manager().regenerateBootstrapToken();
        LocalManagementSession second = bootstrap.manager().exchange(
                InetAddress.getLoopbackAddress(), regenerated.token(), false);
        bootstrap.manager().close();
        assertTrue(bootstrap.manager().isClosed());
        assertThrows(IllegalStateException.class,
                () -> bootstrap.manager().authenticate(second.cookie().value()));
        assertThrows(IllegalStateException.class, bootstrap.manager()::regenerateBootstrapToken);
    }

    @Test
    void rejectsUnsafeSessionLifetimeConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new LocalTokenAuthenticationConfig(
                ManagementAuthenticationMode.LOCAL_TOKEN, Duration.ZERO, Duration.ofHours(8)));
        assertThrows(IllegalArgumentException.class, () -> new LocalTokenAuthenticationConfig(
                ManagementAuthenticationMode.LOCAL_TOKEN, Duration.ofMinutes(30), Duration.ofHours(25)));
        assertThrows(IllegalArgumentException.class, () -> new LocalTokenAuthenticationConfig(
                ManagementAuthenticationMode.LOCAL_TOKEN, Duration.ofHours(9), Duration.ofHours(8)));
    }

    private static void assertNoRetainedRawSecret(Object target, String secret) throws IllegalAccessException {
        for (Field field : target.getClass().getDeclaredFields()) {
            field.setAccessible(true);
            Object value = field.get(target);
            if (value instanceof String text) {
                assertFalse(text.contains(secret), "raw bootstrap token retained in " + field.getName());
            }
            if (value instanceof byte[] bytes) {
                assertFalse(Arrays.equals(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), bytes));
            }
        }
    }

    private static final class SequenceEntropy implements TokenEntropy {
        private int next = 1;

        @Override
        public byte[] nextBytes(int size) {
            byte[] bytes = new byte[size];
            Arrays.fill(bytes, (byte) next++);
            return bytes;
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
