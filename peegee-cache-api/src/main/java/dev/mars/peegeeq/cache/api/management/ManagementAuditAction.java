package dev.mars.peegeeq.cache.api.management;

/** Bounded management operation name for authoritative security auditing. */
public enum ManagementAuditAction {
    REVEAL_ENTRY,
    SET_ENTRY,
    EXPIRE_ENTRY,
    PERSIST_ENTRY,
    TOUCH_ENTRY,
    DELETE_ENTRY,
    SET_COUNTER,
    ADJUST_COUNTER,
    EXPIRE_COUNTER,
    PERSIST_COUNTER,
    DELETE_COUNTER,
    REVEAL_LOCK_OWNER,
    FORCE_RELEASE_LOCK,
    PREVIEW_ENTRY_DELETE,
    EXECUTE_ENTRY_DELETE,
    PREVIEW_COUNTER_DELETE,
    EXECUTE_COUNTER_DELETE
}
