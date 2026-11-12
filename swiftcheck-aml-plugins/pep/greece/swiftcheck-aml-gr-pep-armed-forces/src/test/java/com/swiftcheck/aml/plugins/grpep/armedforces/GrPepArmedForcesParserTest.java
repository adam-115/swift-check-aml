package com.swiftcheck.aml.plugins.grpep.armedforces;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class GrPepArmedForcesParserTest {

    private static final Path RAW = Path.of("src/test/resources/gr-armed-forces-wikipedia.json");

    @Test
    void testConvert() throws Exception {
        GrPepArmedForcesParser parser = new GrPepArmedForcesParser();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(GrPepArmedForcesParser.ID_PREFIX, GrPepArmedForcesParser.TOPIC);

        int count = parser.convert(RAW, dataset);
        assertTrue(count >= 4, "Au moins 4 officiers généraux doivent être extraits, trouvé: " + count);
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertFalse(dataset.entities().isEmpty());

        boolean foundGeneral = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Dimitrios Houpis") || e.getValues("name").contains("Δημήτριος Χούπης"));
        assertTrue(foundGeneral, "Dimitrios Houpis doit être présent dans le dataset");
    }
}
