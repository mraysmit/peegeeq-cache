package dev.mars.peegeeq.cache.rest.security;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.Set;

/** In-memory, single-session local authentication state. */
public final class LocalTokenSessionManager implements AutoCloseable {

    private static final int SECRET_BYTES = 32;
    private static final Base64.Encoder TOKEN_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final LocalTokenAuthenticationConfig config;
    private final Clock clock;
    private final TokenEntropy entropy;
    private byte[] bootstrapDigest;
    private SessionState session;
    private boolean closed;

    private LocalTokenSessionManager(
            LocalTokenAuthenticationConfig config,
            Clock clock,
            TokenEntropy entropy) {
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.entropy = Objects.requireNonNull(entropy, "entropy");
    }

    public static LocalTokenBootstrap start(LocalTokenAuthenticationConfig config) {
        return start(config, Clock.systemUTC(), size -> {
            byte[] bytes = new byte[size];
            new SecureRandom().nextBytes(bytes);
            return bytes;
        });
    }

    static LocalTokenBootstrap start(
            LocalTokenAuthenticationConfig config,
            Clock clock,
            TokenEntropy entropy) {
        LocalTokenSessionManager manager = new LocalTokenSessionManager(config, clock, entropy);
        return manager.issueBootstrapToken();
    }

    public synchronized LocalManagementSession exchange(
            InetAddress immediatePeer,
            String suppliedToken,
            boolean secureTransport) {
        requireOpen();
        Objects.requireNonNull(immediatePeer, "immediatePeer");
        Objects.requireNonNull(suppliedToken, "suppliedToken");
        if (!immediatePeer.isLoopbackAddress()
                || bootstrapDigest == null
                || !MessageDigest.isEqual(bootstrapDigest, digest(suppliedToken))) {
            throw invalidBootstrapToken();
        }

        erase(bootstrapDigest);
        bootstrapDigest = null;
        clearSession();
        Instant now = clock.instant();
        byte[] sessionSecret = entropy.nextBytes(SECRET_BYTES);
        byte[] csrfSecret = entropy.nextBytes(SECRET_BYTES);
        String rawSession = encodeAndErase(sessionSecret);
        session = new SessionState(
                digest(rawSession),
                csrfSecret,
                now,
                now,
                secureTransport);
        return snapshot(rawSession, session);
    }

    public synchronized LocalManagementSession authenticate(String suppliedSessionId) {
        requireOpen();
        Objects.requireNonNull(suppliedSessionId, "suppliedSessionId");
        if (session == null || !MessageDigest.isEqual(session.sessionDigest, digest(suppliedSessionId))) {
            throw authenticationRequired();
        }
        Instant now = clock.instant();
        if (!now.isBefore(session.createdAt.plus(config.absoluteLifetime()))
                || !now.isBefore(session.lastSeenAt.plus(config.idleLifetime()))) {
            clearSession();
            throw new ManagementAuthenticationException(401, "SESSION_EXPIRED", "Management session expired");
        }
        session.lastSeenAt = now;
        return snapshot(suppliedSessionId, session);
    }

    public synchronized void logout(String suppliedSessionId) {
        requireOpen();
        Objects.requireNonNull(suppliedSessionId, "suppliedSessionId");
        if (session == null || !MessageDigest.isEqual(session.sessionDigest, digest(suppliedSessionId))) {
            throw authenticationRequired();
        }
        clearSession();
    }

    /** Controlling-process operation; this method is intentionally not exposed as an HTTP route. */
    public synchronized LocalTokenBootstrap regenerateBootstrapToken() {
        requireOpen();
        clearSession();
        return issueBootstrapToken();
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        clearSession();
        erase(bootstrapDigest);
        bootstrapDigest = null;
        closed = true;
    }

    private LocalTokenBootstrap issueBootstrapToken() {
        requireOpen();
        byte[] secret = entropy.nextBytes(SECRET_BYTES);
        if (secret.length != SECRET_BYTES) {
            erase(secret);
            throw new IllegalStateException("Entropy source returned the wrong number of bytes");
        }
        erase(bootstrapDigest);
        String token = encodeAndErase(secret);
        bootstrapDigest = digest(token);
        return new LocalTokenBootstrap(this, token);
    }

    private LocalManagementSession snapshot(String rawSession, SessionState state) {
        Instant idleExpiry = minimum(
                state.lastSeenAt.plus(config.idleLifetime()),
                state.createdAt.plus(config.absoluteLifetime()));
        ManagementSessionCookie cookie = new ManagementSessionCookie(
                ManagementSessionCookie.COOKIE_NAME,
                rawSession,
                "/",
                true,
                state.secureTransport,
                "Strict",
                idleExpiry);
        AuthenticatedManagementIdentity identity = new AuthenticatedManagementIdentity(
                "local-operator", Set.of("operator", "viewer"), "loopback");
        return new LocalManagementSession(
                identity,
                ManagementAuthenticationMode.LOCAL_TOKEN,
                cookie,
                TOKEN_ENCODER.encodeToString(state.csrfSecret),
                idleExpiry,
                state.createdAt.plus(config.absoluteLifetime()));
    }

    private void clearSession() {
        if (session != null) {
            erase(session.sessionDigest);
            erase(session.csrfSecret);
            session = null;
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Local token session manager is closed");
        }
    }

    private static byte[] digest(String value) {
        return digest(value.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] digest(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String encodeAndErase(byte[] value) {
        try {
            return TOKEN_ENCODER.encodeToString(value);
        } finally {
            erase(value);
        }
    }

    private static Instant minimum(Instant first, Instant second) {
        return first.isBefore(second) ? first : second;
    }

    private static void erase(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }

    private static ManagementAuthenticationException invalidBootstrapToken() {
        return new ManagementAuthenticationException(
                401, "INVALID_BOOTSTRAP_TOKEN", "Invalid bootstrap token");
    }

    private static ManagementAuthenticationException authenticationRequired() {
        return new ManagementAuthenticationException(401, "AUTHENTICATION_REQUIRED", "Authentication required");
    }

    private static final class SessionState {
        private final byte[] sessionDigest;
        private final byte[] csrfSecret;
        private final Instant createdAt;
        private Instant lastSeenAt;
        private final boolean secureTransport;

        private SessionState(
                byte[] sessionDigest,
                byte[] csrfSecret,
                Instant createdAt,
                Instant lastSeenAt,
                boolean secureTransport) {
            this.sessionDigest = sessionDigest;
            this.csrfSecret = csrfSecret;
            this.createdAt = createdAt;
            this.lastSeenAt = lastSeenAt;
            this.secureTransport = secureTransport;
        }
    }
}

@FunctionalInterface
interface TokenEntropy {
    byte[] nextBytes(int size);
}
