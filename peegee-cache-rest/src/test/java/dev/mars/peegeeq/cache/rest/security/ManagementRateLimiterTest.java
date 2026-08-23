package dev.mars.peegeeq.cache.rest.security;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ManagementRateLimiterTest {

    @Test
    void enforcesIndependentActorAndSourceLimitsForEveryGuardedAction() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-22T12:00:00Z"));
        RecordingTelemetry telemetry = new RecordingTelemetry();
        ManagementRateLimiter limiter = new ManagementRateLimiter(
                rules(2, 3), 100, clock, telemetry);

        for (RateLimitAction action : RateLimitAction.values()) {
            limiter.acquire(action, "actor-a", InetAddress.getByName("10.0.0.1"));
            limiter.acquire(action, "actor-a", InetAddress.getByName("10.0.0.1"));
            assertRateLimited(() -> limiter.acquire(
                    action, "actor-a", InetAddress.getByName("10.0.0.2")));

            limiter.acquire(action, "actor-b", InetAddress.getByName("10.0.0.1"));
            assertRateLimited(() -> limiter.acquire(
                    action, "actor-c", InetAddress.getByName("10.0.0.1")));
        }

        assertEquals(RateLimitAction.values().length * 2,
                telemetry.events.stream().filter(event -> event.endsWith(":REJECTED")).count());
    }

    @Test
    void windowExpiryRestoresCapacityAndEvictsExpiredIdentities() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-22T12:00:00Z"));
        ManagementRateLimiter limiter = new ManagementRateLimiter(
                rules(1, 1), 2, clock, RateLimitTelemetry.noop());

        limiter.acquire(RateLimitAction.REVEAL, "a", InetAddress.getByName("10.0.0.1"));
        limiter.acquire(RateLimitAction.REVEAL, "b", InetAddress.getByName("10.0.0.2"));
        assertEquals(2, limiter.actorIdentityCount());
        assertRateLimited(() -> limiter.acquire(
                RateLimitAction.REVEAL, "c", InetAddress.getByName("10.0.0.3")));

        clock.advance(Duration.ofMinutes(1));
        limiter.acquire(RateLimitAction.REVEAL, "c", InetAddress.getByName("10.0.0.3"));
        assertEquals(1, limiter.actorIdentityCount());
        assertEquals(1, limiter.sourceIdentityCount());
    }

    @Test
    void telemetryContainsOnlyBoundedActionAndOutcomeDimensions() throws Exception {
        RecordingTelemetry telemetry = new RecordingTelemetry();
        ManagementRateLimiter limiter = new ManagementRateLimiter(
                rules(1, 1), 10,
                Clock.fixed(Instant.parse("2026-08-22T12:00:00Z"), ZoneOffset.UTC),
                telemetry);

        limiter.acquire(RateLimitAction.PUBLISH, "secret-actor", InetAddress.getByName("10.9.8.7"));
        assertRateLimited(() -> limiter.acquire(
                RateLimitAction.PUBLISH, "secret-actor", InetAddress.getByName("10.9.8.7")));

        assertEquals(List.of("PUBLISH:ACCEPTED", "PUBLISH:REJECTED"), telemetry.events);
        String joined = String.join(",", telemetry.events);
        org.junit.jupiter.api.Assertions.assertFalse(joined.contains("secret-actor"));
        org.junit.jupiter.api.Assertions.assertFalse(joined.contains("10.9.8.7"));
    }

    private static Map<RateLimitAction, RateLimitRule> rules(int actor, int source) {
        return java.util.Arrays.stream(RateLimitAction.values()).collect(java.util.stream.Collectors.toMap(
                action -> action,
                action -> new RateLimitRule(actor, source, Duration.ofMinutes(1))));
    }

    private static void assertRateLimited(ThrowingRunnable action) {
        ManagementRateLimitException failure = assertThrows(ManagementRateLimitException.class, action::run);
        assertEquals(429, failure.status());
        assertEquals("RATE_LIMITED", failure.code());
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static final class RecordingTelemetry implements RateLimitTelemetry {
        private final List<String> events = new ArrayList<>();

        @Override
        public void record(RateLimitAction action, RateLimitOutcome outcome) {
            events.add(action + ":" + outcome);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
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
            return now;
        }
    }
}
