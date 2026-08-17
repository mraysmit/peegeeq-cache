# Release and Maven Central publication

The reactor produces these library artifacts:

- `peegee-cache-api`: stable public service and model contracts;
- `peegee-cache-core`: validation, in-memory snapshots, and vendor-neutral telemetry SPI;
- `peegee-cache-pg`: PostgreSQL repositories, services, and bootstrap SQL;
- `peegee-cache-runtime`: managed lifecycle and optional schema bootstrap;
- `peegee-cache-observability`: Micrometer, OpenTelemetry, and health adapters;
- `peegee-cache-test-support`: reusable Testcontainers and benchmark fixtures.

`peegee-cache-examples` and `peegee-cache-benchmarks` are runnable verification artifacts, not application dependencies.

The `release-artifacts` Maven profile attaches source and Javadoc JARs:

```shell
mvn -Prelease-artifacts clean package
```

Every build enforces Maven 3.9.x, build JDK 21–26, Java 21 bytecode compatibility, dependency convergence, and duplicate dependency declarations. A release candidate must also pass `mvn clean verify`, the benchmark thresholds appropriate to its target environment, and the PostgreSQL compatibility matrix.

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
