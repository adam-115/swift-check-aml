package com.swiftcheck.aml.plugins.grpep.rca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class GrPepRcaParserTest {

    private static final Path RAW = Path.of("src/main/resources/seed/gr-rca.json");

    @Test
    void testConvert() throws Exception {
        GrPepRcaParser parser = new GrPepRcaParser();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(GrPepRcaParser.ID_PREFIX, GrPepRcaParser.TOPIC);

        int count = parser.convert(RAW, dataset);
        assertTrue(count >= 5);
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertFalse(dataset.entities().isEmpty());

        boolean foundMareva = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Mareva Grabowski-Mitsotaki"));
        assertTrue(foundMareva, "Mareva Grabowski-Mitsotaki doit être présente dans le dataset");
    }
}
