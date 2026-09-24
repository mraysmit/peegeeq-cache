# Release and Maven Central publication

**Last reconciled:** 24 September 2026 against `3ed1162`.

The reactor has ten modules. Six are library artifacts:

- `peegee-cache-api`: stable public service and model contracts;
- `peegee-cache-core`: validation, in-memory snapshots, and vendor-neutral telemetry SPI;
- `peegee-cache-pg`: PostgreSQL repositories, services, and bootstrap SQL;
- `peegee-cache-runtime`: managed lifecycle and optional schema bootstrap;
- `peegee-cache-observability`: Micrometer, OpenTelemetry, and health adapters;
- `peegee-cache-test-support`: reusable Testcontainers and benchmark fixtures.

The other four are applications, not dependencies for consuming code:

- `peegee-cache-management-ui`: the React console, packaged as a jar of static assets under `ui/`;
- `peegee-cache-rest`: the management server; it also attaches a shaded `runnable` classifier jar that bundles the console and one SLF4J provider (see [management operations](PEEGEEQ_CACHE_MANAGEMENT_OPERATIONS.md));
- `peegee-cache-examples` and `peegee-cache-benchmarks`: runnable verification artifacts.

**Open decision: what is uploaded to Central.** Neither the parent POM nor any module sets `maven.deploy.skip` or a Central Portal exclusion, so nothing in the configuration keeps the four applications out of the `central-release` bundle. This has not been confirmed by a deploy dry run. Decide whether the applications are published (the management server's runnable jar may be worth publishing; the examples and benchmarks probably are not) and configure the exclusions before the first release.

The `release-artifacts` Maven profile attaches source and Javadoc JARs:

```shell
mvn -Prelease-artifacts clean package
```

(`mvn clean verify -Prelease-artifacts`, under Release commands, is the same profile with the tests and verification checks; use it for a release candidate.)

Every build enforces Maven 3.9.x, build JDK 21–26, Java 21 bytecode compatibility, dependency convergence, and duplicate dependency declarations. A release candidate must also pass `mvn clean verify`, the benchmark thresholds appropriate to its target environment, and the PostgreSQL compatibility matrix (see [operations guidance](PEEGEEQ_CACHE_OPERATIONS.md#compatibility)).

## Default publication configuration

The parent POM supplies Maven Central's required metadata and conservative defaults:

- Apache License 2.0 and the repository `LICENSE` file;
- project, issue tracker, developer, and SCM URLs derived from the canonical GitHub repository;
- Sonatype Central Portal server ID `central`;
- GPG signing with best-practices enforcement;
- manual publication after Central validates the uploaded deployment.

The `central-release` profile attaches source and Javadoc JARs, signs every artifact, and uploads the reactor as one Central Portal bundle. It deliberately sets `autoPublish` to `false`, preserving a final review step in the Portal.

## One-time setup

1. Register at the Sonatype Central Portal and verify control of the `dev.mars` namespace.
2. Generate a Central Portal user token.
3. Copy `docs/maven-settings-central.xml.example` outside the repository or reference it directly with `-s`. Set `CENTRAL_USERNAME` and `CENTRAL_PASSWORD` in the process environment; never commit their values.
4. Install GnuPG and create or import a public signing key. Use `gpg-agent` interactively, or set `MAVEN_GPG_PASSPHRASE` through the CI secret store for unattended builds.
5. Select a non-SNAPSHOT release version and prepare release notes.

## Release commands

Build and inspect release artifacts without signing or uploading:

```shell
mvn clean verify -Prelease-artifacts
```

After setting a non-SNAPSHOT version, sign and upload a deployment for Central validation:

```shell
mvn -s docs/maven-settings-central.xml.example clean deploy -Pcentral-release
```

The command does not automatically publish. Review the validated deployment in the Central Portal and publish it manually. Credentials and private signing material remain outside the POM and source tree.
