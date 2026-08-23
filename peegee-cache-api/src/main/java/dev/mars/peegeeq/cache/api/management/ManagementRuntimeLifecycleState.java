package dev.mars.peegeeq.cache.api.management;

/** Lifecycle of the management HTTP process. */
public enum ManagementRuntimeLifecycleState {
    NEW,
    STARTING,
    RUNNING,
    STOPPING,
    STOPPED,
    FAILED
}
