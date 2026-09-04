package dev.mars.peegeeq.cache.rest.server;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** JUnit resource that starts one PostgreSQL container for the complete browser-test suite. */
final class ManagementBrowserPostgresWorker implements BeforeAllCallback, AfterAllCallback {

    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(ManagementBrowserPostgresWorker.class);
    private static final String RESOURCE_KEY = "postgres-browser-suite";

    private SuiteResource suiteResource;

    @Override
    public void beforeAll(ExtensionContext context) {
        suiteResource = context.getRoot().getStore(NAMESPACE).getOrComputeIfAbsent(
                RESOURCE_KEY,
                ignored -> new SuiteResource(),
                SuiteResource.class);
    }

    PostgreSQLContainer postgres() {
        if (suiteResource == null) {
            throw new IllegalStateException("Browser suite PostgreSQL resource is not running");
        }
        return suiteResource.postgres();
    }

    @Override
    public void afterAll(ExtensionContext context) {
        // The root ExtensionContext owns the resource and closes it after the complete browser suite.
    }

    private static final class SuiteResource implements ExtensionContext.Store.CloseableResource {
        private final ManagementBrowserWorkerResource<PostgreSQLContainer> worker =
                new ManagementBrowserWorkerResource<>(
                        ManagementConsolePostgresFixture::newPostgresWorkerContainer,
                        PostgreSQLContainer::start,
                        PostgreSQLContainer::stop);

        private SuiteResource() {
            worker.start();
        }

        private PostgreSQLContainer postgres() {
            return worker.resource();
        }

        @Override
        public void close() throws Exception {
            PostgreSQLContainer postgres = worker.resource();
            ManagementConsolePostgresFixture.closeShared(postgres);
            worker.close();
        }
    }
}
