package com.swiftcheck.aml.plugins.grpep.diplomats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class GrPepDiplomatsParserTest {

    private static final Path RAW = Path.of("src/test/resources/gr-diplomats-wikidata.json");

    @Test
    void testConvert() throws Exception {
        GrPepDiplomatsParser parser = new GrPepDiplomatsParser();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(GrPepDiplomatsParser.ID_PREFIX, GrPepDiplomatsParser.TOPIC);

        int count = parser.convert(RAW, dataset);
        assertTrue(count >= 10, "Au moins 10 diplomates attendus, obtenu : " + count);
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertFalse(dataset.entities().isEmpty());

        boolean foundAmbassador = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Alexandra Papadopoulou"));
        assertTrue(foundAmbassador, "Alexandra Papadopoulou doit être présente dans le dataset");
    }
}
