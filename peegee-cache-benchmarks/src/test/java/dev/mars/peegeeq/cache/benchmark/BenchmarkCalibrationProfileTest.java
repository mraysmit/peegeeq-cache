package dev.mars.peegeeq.cache.benchmark;

import org.junit.jupiter.api.Test;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

class BenchmarkCalibrationProfileTest {
    @Test
    void optInProfileLaunchesCalibrationWithExplicitHeapAndAllNineArguments() throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var document = factory.newDocumentBuilder().parse(Path.of("pom.xml").toFile());
        var profiles = document.getElementsByTagName("profile");
        org.w3c.dom.Element profile = null;
        for (int index = 0; index < profiles.getLength(); index++) {
            var candidate = (org.w3c.dom.Element) profiles.item(index);
            if (candidate.getElementsByTagName("id").item(0).getTextContent().equals("benchmark-calibration")) profile = candidate;
        }
        assertNotNull(profile, "Calibration must be runnable through the module's standard opt-in Maven workflow");
        var arguments = (org.w3c.dom.Element) profile.getElementsByTagName("arguments").item(0);
        assertEquals("override", arguments.getAttribute("combine.self"));
        var values = new ArrayList<String>();
        var nodes = arguments.getElementsByTagName("argument");
        for (int index = 0; index < nodes.getLength(); index++) values.add(nodes.item(index).getTextContent());
        assertEquals(12, values.size());
        assertEquals("-Xmx${peegeeq.calibration.heap}", values.get(0));
        assertEquals("dev.mars.peegeeq.cache.benchmark.BenchmarkCalibrationMain", values.get(2));
        assertEquals("${peegeeq.calibration.outputDirectory}", values.get(3));
        assertEquals("${peegeeq.calibration.forkIndex}", values.get(11));
        assertEquals(1, arguments.getElementsByTagName("classpath").getLength());
    }
}
