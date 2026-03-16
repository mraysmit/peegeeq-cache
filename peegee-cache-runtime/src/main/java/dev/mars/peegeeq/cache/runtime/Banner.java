package dev.mars.peegeeq.cache.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * Loads and prints the peegee-cache startup banner from the classpath.
 */
public final class Banner {

    private static final Logger log = LoggerFactory.getLogger(Banner.class);
    private static final String BANNER_RESOURCE = "peegee-cache-banner.txt";
    private static final String VERSION_PLACEHOLDER = "${version}";

    private Banner() {}

    /**
     * Prints the banner to the logger at INFO level.
     *
     * @param version the library version string to substitute into the banner
     */
    public static void print(String version) {
        try (InputStream in = Banner.class.getClassLoader().getResourceAsStream(BANNER_RESOURCE)) {
            if (in == null) {
                log.debug("Banner resource '{}' not found on classpath", BANNER_RESOURCE);
                return;
            }
            String banner;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                banner = reader.lines().collect(Collectors.joining(System.lineSeparator()));
            }
            if (version != null) {
                banner = banner.replace(VERSION_PLACEHOLDER, version);
            }
            log.info("\n{}", banner);
        } catch (Exception e) {
            log.debug("Failed to load banner", e);
        }
    }

    /**
     * Prints the banner using the version from the runtime module's package.
     */
    public static void print() {
        String version = Banner.class.getPackage().getImplementationVersion();
        print(version != null ? version : "dev");
    }
}
