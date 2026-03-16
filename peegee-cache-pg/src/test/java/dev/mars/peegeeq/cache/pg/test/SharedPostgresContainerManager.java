package dev.mars.peegeeq.cache.pg.test;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Shared PostgreSQL Testcontainer manager for the pg test suite.
 *
 * Uses reference counting so each test class can acquire/release the same
 * container without leaking it between Maven test runs.
 */
public final class SharedPostgresContainerManager {

    private static final Logger log = LoggerFactory.getLogger(SharedPostgresContainerManager.class);

    private static PostgreSQLContainer<?> sharedContainer;
    private static int refCount;

    private SharedPostgresContainerManager() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static synchronized PostgreSQLContainer<?> acquire(String ownerLabel) {
        if (sharedContainer == null) {
            log.info("Starting shared PostgreSQL Testcontainer for '{}' (image: {})",
                    ownerLabel, PostgreSQLTestConstants.POSTGRES_IMAGE);
            sharedContainer = new PostgreSQLContainer<>(PostgreSQLTestConstants.POSTGRES_IMAGE)
                    .withDatabaseName(PostgreSQLTestConstants.DEFAULT_DATABASE_NAME)
                    .withUsername(PostgreSQLTestConstants.DEFAULT_USERNAME)
                    .withPassword(PostgreSQLTestConstants.DEFAULT_PASSWORD)
                    .withReuse(false);
            sharedContainer.start();
            log.info("Shared PostgreSQL Testcontainer started on {}:{}",
                    sharedContainer.getHost(), sharedContainer.getFirstMappedPort());
        }

        refCount++;
        log.debug("Shared PostgreSQL Testcontainer acquired by '{}' (refCount={})", ownerLabel, refCount);
        return sharedContainer;
    }

    public static synchronized void release(String ownerLabel) {
        if (refCount > 0) {
            refCount--;
        }
        log.debug("Shared PostgreSQL Testcontainer released by '{}' (refCount={})", ownerLabel, refCount);

        if (refCount == 0 && sharedContainer != null) {
            log.info("Stopping shared PostgreSQL Testcontainer after release by '{}'", ownerLabel);
            sharedContainer.stop();
            sharedContainer = null;
        }
    }
}