# peegee-cache Copilot Instructions

## Scope

These instructions apply to the entire `peegee-cache` workspace.

Primary design references:

- `docs/design/peegee-cache-design.md`
- `docs/design/peegee-cache-implementation-plan.md`
- `docs/guidelines/pgq-coding-principles.md`

When those documents and ad hoc guesses conflict, follow the documents.

## Non-negotiable guardrails

- keep the core library pure Vert.x 5.x
- use `io.vertx.core.Future<T>` for public async APIs
- do not introduce `CompletableFuture`, `CompletionStage`, Reactor, Mutiny-as-primary-surface, or Spring-first abstractions into the Phase 1 core API
- keep runtime lifecycle explicit and Vert.x-native
- keep behavior configuration-driven rather than hidden in hardcoded defaults

## TDD and testing rules

- follow strict TDD wherever the behavior is testable
- write or extend a failing test first, then implement the minimum code needed to pass it
- do not move to the next step until the changed behavior is passing tests
- read test logs carefully after every run; do not trust exit code alone
- use Maven `-X` when test behavior is unclear or suspicious
- treat suspiciously fast or empty-success test runs as invalid until actual test execution is confirmed

## Database testing rules

- mocking is prohibited for database-facing behavior
- do not mock PostgreSQL connections, pools, repositories, or SQL execution
- do not use H2, HSQLDB, or in-memory substitutes for PostgreSQL semantics
- always use Testcontainers for migrations, repositories, locking, expiry, counters, SQL behavior, and runtime database integration
- only use pure unit tests for logic with zero database behavior

## Configuration rules

- accept explicit validated configuration from callers
- do not rely on hidden fallback system properties inside library code
- do not hardcode schema names, notification channels, or environment-specific paths in core modules
- configuration should map cleanly to Vert.x and PostgreSQL concepts such as pool sizing, timeouts, schema names, sweeper behavior, and listener behavior

## Implementation priorities

- preserve module boundaries: `api`, `core`, `pg`, `runtime`, `observability`, `test-support`, `examples`
- finish `V1 Core` before remaining `V1` work
- implement database invariants in SQL and schema constraints, not just Java checks
- treat native SQL support as a deliberate contract, not accidental table exposure
- prefer direct Vert.x reactive PostgreSQL client usage in the `pg` module

## Working style

- investigate before changing code
- follow existing repo patterns and the documented PeeGeeQ family conventions
- fix root causes, not symptoms
- keep changes small and verifiable
- document intent honestly
- do not skip failing tests, disable tests, or hide defects behind exclusions

## Current platform assumptions

- development is on Windows 11
- Java baseline is 21+
- Vert.x baseline is 5.0.x