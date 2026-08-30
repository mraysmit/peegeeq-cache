package dev.mars.peegeeq.cache.rest.server;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** JUnit worker resource that starts one reusable PostgreSQL container per browser test class. */
final class ManagementBrowserPostgresWorker implements BeforeAllCallback, AfterAllCallback {

    private final ManagementBrowserWorkerResource<PostgreSQLContainer> worker =
            new ManagementBrowserWorkerResource<>(
                    ManagementConsolePostgresFixture::newPostgresWorkerContainer,
                    PostgreSQLContainer::start,
                    PostgreSQLContainer::stop);

    @Override
    public void beforeAll(ExtensionContext context) {
        worker.start();
    }

    PostgreSQLContainer postgres() {
        return worker.resource();
    }

    @Override
    public void afterAll(ExtensionContext context) {
        worker.close();
    }
}
