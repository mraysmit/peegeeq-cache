# Native PostgreSQL API

The supported native SQL surface consists of the functions and read-only views listed here. Application code must not write directly to the backing tables: their layout is an internal persistence detail and may evolve through bundled migrations.

## Write and coordination functions

Function identity includes every input type, including parameters that have defaults.

| Function signature | Result columns |
|---|---|
| `acquire_lock(text, text, text, bigint, boolean, boolean)` | `acquired boolean, owner_token text, fencing_token bigint, lease_expires_at timestamptz` |
| `renew_lock(text, text, text, bigint)` | `renewed boolean, lease_expires_at timestamptz` |
| `release_lock(text, text, text)` | `released boolean` |
| `increment_counter(text, text, bigint, bigint, text, boolean)` | `counter_value bigint, version bigint` |
| `set_counter(text, text, bigint, bigint)` | `counter_value bigint, version bigint` |
| `delete_counter(text, text)` | `deleted boolean` |
| `set_entry(text, text, text, bytea, bigint, bigint, text, bigint)` | `applied boolean, version bigint` |
| `delete_entry(text, text)` | `deleted boolean` |

`set_entry` accepts modes `UPSERT`, `ONLY_IF_ABSENT`, `ONLY_IF_PRESENT`, and `ONLY_IF_VERSION_MATCHES`. `increment_counter` accepts TTL modes `PRESERVE_EXISTING`, `REPLACE`, and `REMOVE`. TTL values are milliseconds; `NULL` means no requested TTL value where permitted by the selected mode.

Callers should schema-qualify every function. For example:

```sql
SELECT *
FROM peegee_cache.increment_counter(
    'rate-limit', 'account-42', 1, 60000, 'REPLACE', true
);
```

## Stable read views

- `live_entries` exposes non-expired cache entries.
- `live_counters` exposes non-expired counters.
- `active_locks` exposes leases whose expiry is still in the future.

These views are the supported direct-read contract. The backing tables (`cache_entries`, `cache_counters`, and `cache_locks`) remain observable for database administration, but their columns and constraints are not a compatibility API for application queries.

## Versioning and upgrades

The unreleased 0.1.0 line has one consolidated `V001` baseline containing the schema, functions, stable read views, and migration ledger. New numbered migrations begin only after that baseline has shipped. Bundled migrations are ordered and recorded in `schema_migrations`. Managed startup with `SchemaBootstrapMode.APPLY` takes a PostgreSQL advisory lock, applies each missing migration in its own transaction, records it, and never automatically removes user data or rolls back a version. Reapplying the migrator is idempotent.

`BootstrapSqlRenderer.loadForSchema(schema)` returns the complete ordered migration set for deployment tools that own schema changes externally. Individual migrations remain available through `loadMigrationForSchema(version, schema)` for controlled upgrade workflows.

Before 1.0, additions are preferred, but a minor release may make a breaking SQL change when it includes an explicit forward migration and release note. After 1.0, removing or incompatibly changing a documented function signature or view requires a major release. Consumers should use the exact signatures above and must not depend on undocumented overloads or backing-table layouts.

Rollback is operational rather than automatic: stop writers, restore a tested database backup or execute a release-specific rollback procedure, then deploy the matching library version. The migrator intentionally has no destructive downgrade command.
