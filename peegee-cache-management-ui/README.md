# PeeGeeQ Cache Management UI

This module owns the production React console, generated TypeScript contract types, frontend tests, and the compiled `ui/*` webroot artifact. The root Maven reactor is the authoritative build entry point.

## User guide

The [User Journeys Guide](docs/USER_JOURNEYS_GUIDE.md) covers 16 complete processes with detailed
worked explanations, example inputs, expected screen states, decision points, completion checks,
and recovery guidance. It also explains scope, versions, TTL, permissions, and safe retry behavior.

## Screenshots

Open [the screenshot gallery](docs/screenshots/index.html) for visible previews by feature and behavior.
The PNGs are in the flat [documentation screenshot directory](docs/screenshots/README.md), using descriptive
filenames rather than scenario IDs. The complete passing browser run refreshes this gallery automatically.
Captures show the actual development UI without screenshot masks, redaction, or replacement values.

## Build and verify

From the repository root:

```powershell
mvn -pl :peegee-cache-management-ui verify
```

Maven installs Node 22.22.2 and npm 10.9.4 into this module, runs `npm ci`, generates types from the authoritative management OpenAPI document, performs type and lint checks, runs Vitest with the coverage gate, builds Vite production resources, packages the UI JAR, and validates its contents. A global Node or npm installation is not required.

The complete project gate remains:

```powershell
mvn verify
```

## Frontend commands

After Maven has installed the pinned local toolchain, commands can be run from this module. On Windows, prepend the pinned executable directory before invoking npm so child command shims also resolve Node 22 rather than a workstation Node earlier on `PATH`:

```powershell
$uiNodeDir = (Resolve-Path '.\node').Path
$env:Path = "$uiNodeDir;$env:Path"
& '.\node\npm.cmd' run verify
```

The `preverify` check fails fast and reports the resolved executable when the wrong Node runtime reaches the verification gate.

Available npm commands are:

- `run generate:openapi` — generate build-only TypeScript contract types under `target/generated-sources/openapi`;
- `run quality` — run TypeScript and ESLint checks;
- `run test:run` — run the deterministic frontend test suite;
- `run test:coverage` — run the same suite with V8 coverage; the build fails below 80 percent branch coverage on `src/api/**` and `src/state/**` (reports under `target/coverage`);
- `run build` — create the production webroot under `target/classes/ui`;
- `run verify` — run generation, quality, tests, and the production build.

Generated types, installed tools, dependencies, reports, and compiled assets remain ignored build output. `package.json`, `package-lock.json`, source, tests, and configuration are committed.

## Directives enforced by `test/quality`

Five static guard tests run in the default Vitest gate and encode the mandates in `docs/guidelines/PEEGEEQ_CACHE_TEST_COVERAGE_AND_TDD_APPROACH.md` §5 and `docs/design/PEEGEEQ_CACHE_MANAGEMENT_UI_DESIGN.md` §3.1: UI controls come from Ant Design 5 and charts from Recharts (`component-library.guard`); no test doubles of any kind and no client/port props on pages (`no-test-fakes.guard`); no transport construction outside `src/api` and no `axios` (`no-direct-transport.guard`); every served fixture body is produced by a production Zod schema and every page test is served by `test/support/loopback-server` (`zod-fixture.guard`); sensitive DTOs never reach stores, RTK Query slices, persistence, URL builders, or logging (`sensitive-dto.guard`). See phase U11 of `docs/design/PEEGEEQ_CACHE_MANAGEMENT_UI_IMPLEMENTATION_PLAN.md`.

The production console must never persist session CSRF proof, setup passwords, bootstrap tokens, cache values, lock owners, or pub/sub payloads. Phase-specific behavior and evidence requirements are defined in `docs/design/PEEGEEQ_CACHE_MANAGEMENT_UI_IMPLEMENTATION_PLAN.md`.
