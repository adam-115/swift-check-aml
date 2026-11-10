package com.swiftcheck.aml.plugins.grpep.supremecourts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class GrPepSupremeCourtsParserTest {

    private static final Path RAW = Path.of("src/main/resources/seed/gr-supreme-courts.json");

    @Test
    void testConvert() throws Exception {
        GrPepSupremeCourtsParser parser = new GrPepSupremeCourtsParser();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(GrPepSupremeCourtsParser.ID_PREFIX, GrPepSupremeCourtsParser.TOPIC);

        int count = parser.convert(RAW, dataset);
        assertTrue(count >= 5);
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertFalse(dataset.entities().isEmpty());

        boolean foundJudge = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Ioanna Klapa-Christodoulea"));
        assertTrue(foundJudge, "Ioanna Klapa-Christodoulea doit être présente dans le dataset");
    }
}
