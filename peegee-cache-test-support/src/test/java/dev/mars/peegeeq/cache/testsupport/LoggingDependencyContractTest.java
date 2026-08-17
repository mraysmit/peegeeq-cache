package dev.mars.peegeeq.cache.testsupport;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class LoggingDependencyContractTest {

    private static final List<String> LIBRARY_MODULES = List.of(
            "peegee-cache-api",
            "peegee-cache-core",
            "peegee-cache-pg",
            "peegee-cache-runtime",
            "peegee-cache-observability",
            "peegee-cache-test-support"
    );

    private static final Set<String> LOGGING_PROVIDERS = Set.of(
            "org.slf4j:slf4j-simple",
            "org.slf4j:slf4j-jdk14",
            "org.slf4j:slf4j-jdk-platform-logging",
            "org.slf4j:slf4j-reload4j",
            "org.slf4j:slf4j-nop",
            "ch.qos.logback:logback-classic",
            "org.apache.logging.log4j:log4j-slf4j2-impl",
            "org.apache.logging.log4j:log4j-slf4j-impl",
            "org.tinylog:slf4j-tinylog"
    );

    @Test
    void publishedLibrariesUseCurrentApiWithoutChoosingAProvider() throws Exception {
        Path repositoryRoot = repositoryRoot();
        Element parent = parse(repositoryRoot.resolve("pom.xml"));
        assertEquals("2.0.18", directChildText(directChild(parent, "properties"), "slf4j.version"));

        for (String module : LIBRARY_MODULES) {
            Element project = parse(repositoryRoot.resolve(module).resolve("pom.xml"));
            NodeList dependencies = project.getElementsByTagName("dependency");
            for (int index = 0; index < dependencies.getLength(); index++) {
                Element dependency = (Element) dependencies.item(index);
                String groupId = directChildText(dependency, "groupId");
                String artifactId = directChildText(dependency, "artifactId");
                String scope = optionalDirectChildText(dependency, "scope");
                String coordinates = groupId + ":" + artifactId;
                assertFalse(LOGGING_PROVIDERS.contains(coordinates) && !"test".equals(scope),
                        () -> module + " must not select logging provider " + artifactId);
            }
        }
    }

    private static Path repositoryRoot() {
        String configuredRoot = System.getProperty("maven.multiModuleProjectDirectory");
        if (configuredRoot != null && !configuredRoot.isBlank()) {
            return Path.of(configuredRoot);
        }

        Path current = Path.of("").toAbsolutePath().normalize();
        return current.getFileName().toString().equals("peegee-cache-test-support")
                ? current.getParent()
                : current;
    }

    private static Element parse(Path pom) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(pom.toFile()).getDocumentElement();
    }

    private static Element directChild(Element parent, String name) {
        for (int index = 0; index < parent.getChildNodes().getLength(); index++) {
            if (parent.getChildNodes().item(index) instanceof Element child
                    && name.equals(child.getTagName())) {
                return child;
            }
        }
        throw new IllegalStateException("Missing " + name + " in " + parent.getTagName());
    }

    private static String directChildText(Element parent, String name) {
        return directChild(parent, name).getTextContent().trim();
    }

    private static String optionalDirectChildText(Element parent, String name) {
        for (int index = 0; index < parent.getChildNodes().getLength(); index++) {
            if (parent.getChildNodes().item(index) instanceof Element child
                    && name.equals(child.getTagName())) {
                return child.getTextContent().trim();
            }
        }
        return "";
    }
}
