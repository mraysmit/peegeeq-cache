package dev.mars.peegeeq.cache.rest.security;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

/** Fixed-window actor/source limiter with hard-bounded identity state. */
public final class ManagementRateLimiter {

    private final Map<RateLimitAction, RateLimitRule> rules;
    private final int maximumTrackedIdentities;
    private final Clock clock;
    private final RateLimitTelemetry telemetry;
    private final Map<IdentityKey, WindowCounter> actors = new HashMap<>();
    private final Map<IdentityKey, WindowCounter> sources = new HashMap<>();

    public ManagementRateLimiter(
            Map<RateLimitAction, RateLimitRule> rules,
            int maximumTrackedIdentities,
            Clock clock,
            RateLimitTelemetry telemetry) {
        Objects.requireNonNull(rules, "rules");
        EnumMap<RateLimitAction, RateLimitRule> copiedRules = new EnumMap<>(RateLimitAction.class);
        copiedRules.putAll(rules);
        if (copiedRules.size() != RateLimitAction.values().length) {
            throw new IllegalArgumentException("Every guarded management action requires a rate-limit rule");
        }
        this.rules = Map.copyOf(copiedRules);
        if (maximumTrackedIdentities < 1) {
            throw new IllegalArgumentException("maximumTrackedIdentities must be positive");
        }
        this.maximumTrackedIdentities = maximumTrackedIdentities;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
    }

    public synchronized void acquire(RateLimitAction action, String actor, InetAddress sourceAddress) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(sourceAddress, "sourceAddress");
        RateLimitRule rule = rules.get(action);
        Instant now = clock.instant();
        evictExpired(actors, now);
        evictExpired(sources, now);

        IdentityKey actorKey = new IdentityKey(action, fingerprint(actor));
        IdentityKey sourceKey = new IdentityKey(action, fingerprint(sourceAddress.getHostAddress()));
        WindowCounter actorCounter = actors.get(actorKey);
        WindowCounter sourceCounter = sources.get(sourceKey);
        boolean actorCapacity = actorCounter != null || actors.size() < maximumTrackedIdentities;
        boolean sourceCapacity = sourceCounter != null || sources.size() < maximumTrackedIdentities;
        boolean accepted = actorCapacity
                && sourceCapacity
                && (actorCounter == null || actorCounter.count < rule.actorLimit())
                && (sourceCounter == null || sourceCounter.count < rule.sourceLimit());
        if (!accepted) {
            record(action, RateLimitOutcome.REJECTED);
            throw new ManagementRateLimitException();
        }

        actors.computeIfAbsent(actorKey, ignored -> new WindowCounter(now.plus(rule.window()))).count++;
        sources.computeIfAbsent(sourceKey, ignored -> new WindowCounter(now.plus(rule.window()))).count++;
        record(action, RateLimitOutcome.ACCEPTED);
    }

    public synchronized int actorIdentityCount() {
        evictExpired(actors, clock.instant());
        return actors.size();
    }

    public synchronized int sourceIdentityCount() {
        evictExpired(sources, clock.instant());
        return sources.size();
    }

    private void record(RateLimitAction action, RateLimitOutcome outcome) {
        try {
            telemetry.record(action, outcome);
        } catch (RuntimeException ignored) {
            // Exporter failures must not replace the security decision.
        }
    }

    private static void evictExpired(Map<IdentityKey, WindowCounter> counters, Instant now) {
        Iterator<WindowCounter> values = counters.values().iterator();
        while (values.hasNext()) {
            if (!now.isBefore(values.next().expiresAt)) {
                values.remove();
            }
        }
    }

    private static String fingerprint(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record IdentityKey(RateLimitAction action, String fingerprint) { }

    private static final class WindowCounter {
        private int count;
        private final Instant expiresAt;

        private WindowCounter(Instant expiresAt) {
            this.expiresAt = expiresAt;
        }
    }
}
