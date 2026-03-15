package dev.mars.peegeeq.cache.api.exception;

public class LockNotHeldException extends CacheException {
    public LockNotHeldException(String message) { super(message); }
}
