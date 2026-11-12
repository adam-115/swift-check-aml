package com.swiftcheck.aml.plugins.grpep.government;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class GrPepGovernmentParserTest {

    private static final Path RAW = Path.of("src/test/resources/gr-government-wikipedia.json");

    @Test
    void testConvert() throws Exception {
        GrPepGovernmentParser parser = new GrPepGovernmentParser();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(GrPepGovernmentParser.ID_PREFIX, GrPepGovernmentParser.TOPIC);

        int count = parser.convert(RAW, dataset);
        assertTrue(count >= 15, "Au moins 15 membres du gouvernement attendus, obtenu : " + count);
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertFalse(dataset.entities().isEmpty());

        boolean foundPM = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Kyriakos Mitsotakis"));
        assertTrue(foundPM, "Kyriakos Mitsotakis doit être présent dans le dataset");
    }
}
