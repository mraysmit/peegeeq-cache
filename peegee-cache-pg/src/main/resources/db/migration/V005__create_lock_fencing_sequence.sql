-- V005: Create the lock fencing sequence.
-- Monotonic fencing tokens for lease-lock write-ordering safety.
-- Called via NEXTVAL('peegee_cache.lock_fencing_seq') when
-- LockAcquireRequest.issueFencingToken is true.

CREATE SEQUENCE IF NOT EXISTS peegee_cache.lock_fencing_seq;
