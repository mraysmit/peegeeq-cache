package dev.mars.peegeeq.cache.api.management;

/** Resolves a secret reference to a disposable byte array supplied outside configuration DTOs. */
@FunctionalInterface
public interface ManagementSecretProvider {
    byte[] resolve(ManagementSecretReference reference);
}
