package dev.mars.peegeeq.cache.runtime.bootstrap;

/** Controls whether managed runtime startup applies the idempotent bootstrap SQL. */
public enum SchemaBootstrapMode {
    /** The caller or deployment system owns schema provisioning. */
    EXTERNAL,
    /** Runtime startup applies the bundled idempotent bootstrap SQL before background components start. */
    APPLY
}
