package dev.mars.peegeeq.cache.test;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Reference-counted PostgreSQL container shared within one test JVM. */
public final class SharedPostgresContainerManager {

    private static final Logger log = LoggerFactory.getLogger(SharedPostgresContainerManager.class);
    private static PostgreSQLContainer sharedContainer;
    private static int refCount;

    private SharedPostgresContainerManager() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static synchronized PostgreSQLContainer acquire(String ownerLabel) {
        if (sharedContainer == null) {
            String postgresImage = PostgreSQLTestConstants.postgresImage();
            log.info("Starting shared PostgreSQL Testcontainer for '{}' (image: {})",
                    ownerLabel, postgresImage);
            sharedContainer = new PostgreSQLContainer(postgresImage)
                    .withDatabaseName(PostgreSQLTestConstants.DEFAULT_DATABASE_NAME)
                    .withUsername(PostgreSQLTestConstants.DEFAULT_USERNAME)
                    .withPassword(PostgreSQLTestConstants.DEFAULT_PASSWORD)
                    .withReuse(false);
            sharedContainer.start();
        }
        refCount++;
        return sharedContainer;
    }

    public static synchronized void release(String ownerLabel) {
        if (refCount > 0) {
            refCount--;
        }
        if (refCount == 0 && sharedContainer != null) {
            log.info("Stopping shared PostgreSQL Testcontainer after release by '{}'", ownerLabel);
            sharedContainer.stop();
            sharedContainer = null;
        }
    }
}
