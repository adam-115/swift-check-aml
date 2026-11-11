package com.swiftcheck.aml.plugins.plpep.armedforces;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class PlPepArmedForcesParserTest {

    private static final Path FIXTURE = Path.of("src/test/resources/mon-kierownictwo.html");

    @Test
    void extractsOnlyGeneralOfficersFromRealPage() throws Exception {
        List<String[]> found = PlPepArmedForcesParser.extract(Files.readString(FIXTURE, StandardCharsets.UTF_8));
        assertEquals(1, found.size());
        assertEquals("Wiesław Kukuła", found.get(0)[0]);
        assertTrue(found.get(0)[1].contains("Szef Sztabu Generalnego"));
    }

    @Test
    void convertsExtractedRecords(@TempDir Path dir) throws Exception {
        List<String[]> found = PlPepArmedForcesParser.extract(Files.readString(FIXTURE, StandardCharsets.UTF_8));
        ObjectMapper om = new ObjectMapper();
        var root = om.createObjectNode();
        var arr = root.putArray("generals");
        for (String[] f : found) {
            arr.addObject().put("name", f[0]).put("position", f[1]).put("branch", "Sztab Generalny WP");
        }
        Path raw = dir.resolve("raw.json");
        Files.writeString(raw, om.writeValueAsString(root), StandardCharsets.UTF_8);

        FtmDatasetBuilder dataset = new FtmDatasetBuilder("pl-pep-armed-forces", "role.pep");
        assertEquals(1, new PlPepArmedForcesParser().convert(raw, dataset));
        assertEquals(0, dataset.qualityReport().getTotalRejected());
    }
}
