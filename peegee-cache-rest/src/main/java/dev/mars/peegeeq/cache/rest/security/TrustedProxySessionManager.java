package dev.mars.peegeeq.cache.rest.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

/** Bounded single-browser-session implementation for trusted-proxy identity binding. */
public final class TrustedProxySessionManager implements AutoCloseable {

    private static final int SECRET_BYTES = 32;
    private static final Duration MAX_ABSOLUTE = Duration.ofHours(24);
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final Duration idleLifetime;
    private final Duration absoluteLifetime;
    private final Clock clock;
    private final TokenEntropy entropy;
    private State state;
    private boolean closed;

    public static TrustedProxySessionManager createDefault() {
        return new TrustedProxySessionManager(
                Duration.ofMinutes(30),
                Duration.ofHours(8),
                Clock.systemUTC(),
                size -> {
                    byte[] bytes = new byte[size];
                    new SecureRandom().nextBytes(bytes);
                    return bytes;
                });
    }

    TrustedProxySessionManager(
            Duration idleLifetime,
            Duration absoluteLifetime,
            Clock clock,
            TokenEntropy entropy) {
        this.idleLifetime = requirePositive(idleLifetime, "idleLifetime");
        this.absoluteLifetime = requirePositive(absoluteLifetime, "absoluteLifetime");
        if (this.idleLifetime.compareTo(this.absoluteLifetime) > 0
                || this.absoluteLifetime.compareTo(MAX_ABSOLUTE) > 0) {
            throw new IllegalArgumentException("Invalid trusted-proxy session lifetimes");
        }
        this.clock = Objects.requireNonNull(clock, "clock");
        this.entropy = Objects.requireNonNull(entropy, "entropy");
    }

    public synchronized TrustedProxyManagementSession createOrRefresh(
            AuthenticatedManagementIdentity identity,
            String suppliedSessionId,
            boolean secureTransport) {
        requireOpen();
        Objects.requireNonNull(identity, "identity");
        Instant now = clock.instant();
        if (state != null && isExpired(state, now)) {
            clear();
        }
        if (state != null
                && suppliedSessionId != null
                && matches(state.sessionDigest, suppliedSessionId)
                && state.identity.equals(identity)) {
            state.lastSeenAt = now;
            return snapshot(suppliedSessionId, state);
        }
        clear();
        return create(identity, secureTransport, now);
    }

    public synchronized TrustedProxyManagementSession authenticate(
            String suppliedSessionId,
            AuthenticatedManagementIdentity currentIdentity) {
        requireOpen();
        Objects.requireNonNull(suppliedSessionId, "suppliedSessionId");
        Objects.requireNonNull(currentIdentity, "currentIdentity");
        Instant now = clock.instant();
        if (state == null || isExpired(state, now)
                || !matches(state.sessionDigest, suppliedSessionId)
                || !state.identity.equals(currentIdentity)) {
            if (state != null && (isExpired(state, now) || !state.identity.equals(currentIdentity))) {
                clear();
            }
            throw authenticationRequired();
        }
        state.lastSeenAt = now;
        return snapshot(suppliedSessionId, state);
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            clear();
            closed = true;
        }
    }

    private TrustedProxyManagementSession create(
            AuthenticatedManagementIdentity identity,
            boolean secureTransport,
            Instant now) {
        byte[] sessionBytes = requireEntropy();
        String rawSession = encodeAndErase(sessionBytes);
        byte[] csrf = requireEntropy();
        state = new State(digest(rawSession), csrf, identity, now, now, secureTransport);
        return snapshot(rawSession, state);
    }

    private TrustedProxyManagementSession snapshot(String rawSession, State current) {
        Instant absoluteExpiry = current.createdAt.plus(absoluteLifetime);
        Instant idleExpiry = current.lastSeenAt.plus(idleLifetime);
        if (absoluteExpiry.isBefore(idleExpiry)) {
            idleExpiry = absoluteExpiry;
        }
        return new TrustedProxyManagementSession(
                current.identity,
                new ManagementSessionCookie(
                        ManagementSessionCookie.COOKIE_NAME,
                        rawSession,
                        "/",
                        true,
                        current.secureTransport,
                        "Strict",
                        idleExpiry),
                ENCODER.encodeToString(current.csrfSecret),
                idleExpiry,
                absoluteExpiry);
    }

    private boolean isExpired(State current, Instant now) {
        return !now.isBefore(current.createdAt.plus(absoluteLifetime))
                || !now.isBefore(current.lastSeenAt.plus(idleLifetime));
    }

    private byte[] requireEntropy() {
        byte[] bytes = entropy.nextBytes(SECRET_BYTES);
        if (bytes.length != SECRET_BYTES) {
            erase(bytes);
            throw new IllegalStateException("Entropy source returned the wrong number of bytes");
        }
        return bytes;
    }

    private void clear() {
        if (state != null) {
            erase(state.sessionDigest);
            erase(state.csrfSecret);
            state = null;
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Trusted proxy session manager is closed");
        }
    }

    private static boolean matches(byte[] digest, String rawValue) {
        return MessageDigest.isEqual(digest, digest(rawValue));
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String encodeAndErase(byte[] value) {
        try {
            return ENCODER.encodeToString(value);
        } finally {
            erase(value);
        }
    }

    private static void erase(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }

    private static Duration requirePositive(Duration value, String field) {
        Duration duration = Objects.requireNonNull(value, field);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return duration;
    }

    private static ManagementAuthenticationException authenticationRequired() {
        return new ManagementAuthenticationException(401, "AUTHENTICATION_REQUIRED", "Authentication required");
    }

    private static final class State {
        private final byte[] sessionDigest;
        private final byte[] csrfSecret;
        private final AuthenticatedManagementIdentity identity;
        private final Instant createdAt;
        private Instant lastSeenAt;
        private final boolean secureTransport;

        private State(
                byte[] sessionDigest,
                byte[] csrfSecret,
                AuthenticatedManagementIdentity identity,
                Instant createdAt,
                Instant lastSeenAt,
                boolean secureTransport) {
            this.sessionDigest = sessionDigest;
            this.csrfSecret = csrfSecret;
            this.identity = identity;
            this.createdAt = createdAt;
            this.lastSeenAt = lastSeenAt;
            this.secureTransport = secureTransport;
        }
    }
}
