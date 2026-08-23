package dev.mars.peegeeq.cache.api.management;

/** Bounded resource category for management audit events. */
public enum ManagementResourceType {
    SETUP,
    NAMESPACE,
    ENTRY,
    COUNTER,
    LOCK,
    BULK_SELECTION,
    PUBSUB_CHANNEL,
    PUBSUB_SUBSCRIPTION,
    PUBSUB_MESSAGE
}
