package com.swiftcheck.aml.plugins.grpep.president;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class GrPepPresidentParserTest {

    private static final Path RAW = Path.of("src/test/resources/gr-president-wikidata.json");

    @Test
    void testConvert() throws Exception {
        GrPepPresidentParser parser = new GrPepPresidentParser();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(GrPepPresidentParser.ID_PREFIX, GrPepPresidentParser.TOPIC);

        int count = parser.convert(RAW, dataset);
        assertTrue(count >= 5, "Au moins 5 présidents attendus, obtenu : " + count);
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertFalse(dataset.entities().isEmpty());

        boolean foundPresident = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Katerina Sakellaropoulou"));
        assertTrue(foundPresident, "Katerina Sakellaropoulou doit être présente dans le dataset");
    }
}
