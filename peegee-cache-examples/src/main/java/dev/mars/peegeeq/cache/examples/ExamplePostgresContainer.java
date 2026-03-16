package dev.mars.peegeeq.cache.examples;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SuppressWarnings("deprecation")
final class ExamplePostgresContainer extends PostgreSQLContainer<ExamplePostgresContainer> {

    ExamplePostgresContainer(String imageName) {
        super(DockerImageName.parse(imageName));
    }
}