# Guidelines Index

This folder currently contains a mix of:

- local guidance adopted for `peegee-cache`
- imported reference material copied from the sibling `peegeeq` repository

Use the files with that distinction in mind.

## Local-first guidance

- `pgq-coding-principles.md`: primary coding and testing guidance carried into this repo
- `VERTX_MULTI-STATEMENT_SQL_BUG_ANALYSIS.md`: imported postmortem adapted with tenant-isolation notes for this repo

## Imported reference material

- `Vertx-5x-Patterns-Guide.md`: style and architecture reference from sibling PeeGeeQ modules
- `MAVEN_TOOLCHAINS_EXPLAINED.md`: generic Maven toolchains explanation; examples are illustrative, not machine-specific requirements
- `PEEGEEQ_CRASH_RECOVERY_GUIDE.md`: sibling-repo recovery design reference
- `SHUTDOWN_AND_LIFECYCLE_FIXES.md`: sibling-repo lifecycle postmortem reference

When a document mentions module paths such as `peegeeq-db` or `peegeeq-service-manager`, assume those references are to the sibling repository unless the path also exists in `peegee-cache`.