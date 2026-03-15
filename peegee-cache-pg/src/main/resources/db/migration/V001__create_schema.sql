-- V001: Create the peegee_cache schema.
-- All Phase 1 tables, sequences, and indexes reside in this schema
-- to isolate cache infrastructure from application tables.

CREATE SCHEMA IF NOT EXISTS peegee_cache;
