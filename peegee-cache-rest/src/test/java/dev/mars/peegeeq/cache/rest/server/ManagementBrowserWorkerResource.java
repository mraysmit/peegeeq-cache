package dev.mars.peegeeq.cache.rest.server;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Owns exactly one reusable external resource for the lifetime of a browser-test worker. */
final class ManagementBrowserWorkerResource<T> implements AutoCloseable {

    private final Supplier<T> factory;
    private final Consumer<T> starter;
    private final Consumer<T> stopper;
    private T resource;

    ManagementBrowserWorkerResource(Supplier<T> factory, Consumer<T> starter, Consumer<T> stopper) {
        this.factory = Objects.requireNonNull(factory);
        this.starter = Objects.requireNonNull(starter);
        this.stopper = Objects.requireNonNull(stopper);
    }

    synchronized void start() {
        if (resource != null) {
            return;
        }
        T candidate = Objects.requireNonNull(factory.get(), "Worker resource factory returned null");
        starter.accept(candidate);
        resource = candidate;
    }

    synchronized T resource() {
        if (resource == null) {
            throw new IllegalStateException("Browser worker resource is not running");
        }
        return resource;
    }

    @Override
    public synchronized void close() {
        if (resource == null) {
            return;
        }
        T closing = resource;
        resource = null;
        stopper.accept(closing);
    }
}
