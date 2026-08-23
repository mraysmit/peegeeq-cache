package dev.mars.peegeeq.cache.rest.server;

import dev.mars.peegeeq.cache.api.management.ManagementSecretReference;
import dev.mars.peegeeq.cache.rest.security.SetupTargetPolicy;

import java.nio.file.Path;
import java.util.Set;

final class TestManagementConfigurations {

    private TestManagementConfigurations() {
    }

    static ManagementServerConfiguration local(int port) {
        return ManagementServerConfiguration.localToken(
                "127.0.0.1", port, "http://127.0.0.1:" + port,
                SetupTargetPolicy.privateNetworks(
                        Set.of("internal.example"), Set.of("10.0.0.0/8"),
                        Set.of(5432), Set.of("corp-ca")),
                Path.of("logs", "management-audit.jsonl"),
                new ManagementSecretReference("env:PGQ_AUDIT_KEY"));
    }
}
