package dev.mars.peegeeq.cache.rest.server;

import java.util.Arrays;
import java.util.Objects;

/** Explicit owner of bounded-lifetime UI-session secret bytes. */
public final class SetupSecret implements AutoCloseable {

    private byte[] bytes;

    private SetupSecret(byte[] bytes) {
        this.bytes = Objects.requireNonNull(bytes, "bytes");
        if (bytes.length == 0) {
            throw new IllegalArgumentException("Setup secret cannot be empty");
        }
    }

    public static SetupSecret owned(byte[] bytes) {
        return new SetupSecret(bytes);
    }

    public synchronized byte[] copy() {
        if (bytes == null) {
            throw new IllegalStateException("Setup secret is no longer available");
        }
        return bytes.clone();
    }

    public synchronized boolean isCleared() {
        return bytes == null;
    }

    @Override
    public synchronized void close() {
        if (bytes != null) {
            Arrays.fill(bytes, (byte) 0);
            bytes = null;
        }
    }

    @Override
    public String toString() {
        return "SetupSecret[redacted]";
    }
}
