# PeeGeeQ Cache Management UI

This module owns the production React console, generated TypeScript contract types, frontend tests, and the compiled `ui/*` webroot artifact. The root Maven reactor is the authoritative build entry point.

## Build and verify

From the repository root:

```powershell
mvn -pl :peegee-cache-management-ui verify
```

Maven installs Node 22.22.2 and npm 10.9.4 into this module, runs `npm ci`, generates types from the authoritative management OpenAPI document, performs type and lint checks, runs Vitest, builds Vite production resources, packages the UI JAR, and validates its contents. A global Node or npm installation is not required.

The complete project gate remains:

```powershell
mvn verify
```

## Frontend commands

After Maven has installed the pinned local toolchain, commands can be run from this module with `node/npm.cmd`:

- `run generate:openapi` — generate build-only TypeScript contract types under `target/generated-sources/openapi`;
- `run quality` — run TypeScript and ESLint checks;
- `run test:run` — run the deterministic frontend test suite;
- `run build` — create the production webroot under `target/classes/ui`;
- `run verify` — run generation, quality, tests, and the production build.

Generated types, installed tools, dependencies, reports, and compiled assets remain ignored build output. `package.json`, `package-lock.json`, source, tests, and configuration are committed.

The production console must never persist session CSRF proof, setup passwords, bootstrap tokens, cache values, lock owners, or pub/sub payloads. Phase-specific behavior and evidence requirements are defined in `docs/design/PEEGEEQ_CACHE_MANAGEMENT_UI_IMPLEMENTATION_PLAN.md`.
