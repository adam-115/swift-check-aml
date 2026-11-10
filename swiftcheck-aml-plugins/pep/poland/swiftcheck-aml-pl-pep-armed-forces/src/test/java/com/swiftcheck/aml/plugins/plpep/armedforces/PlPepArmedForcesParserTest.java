package com.swiftcheck.aml.plugins.plpep.armedforces;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class PlPepArmedForcesParserTest {

    private static final Path RAW = Path.of("src/main/resources/seed/pl-armed-forces.json");

    @Test
    void testConvert() throws Exception {
        PlPepArmedForcesParser parser = new PlPepArmedForcesParser();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder("pl-pep-armed-forces", "role.pep");

        int count = parser.convert(RAW, dataset);
        assertEquals(10, count);
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertFalse(dataset.entities().isEmpty());

        boolean foundKukula = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Wiesław Kukuła"));
        assertTrue(foundKukula, "Wiesław Kukuła doit être présent dans le dataset");
    }
}

