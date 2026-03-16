package dev.mars.peegeeq.cache.pg.test;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SuppressWarnings("deprecation")
public final class PgTestPostgresContainer extends PostgreSQLContainer<PgTestPostgresContainer> {

    public PgTestPostgresContainer(String imageName) {
        super(DockerImageName.parse(imageName));
    }
}