package com.swiftcheck.aml.plugins.grpep.stateenterprises;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class GrPepStateEnterprisesParserTest {

    private static final Path RAW = Path.of("src/main/resources/seed/gr-state-enterprises.json");

    @Test
    void testConvert() throws Exception {
        GrPepStateEnterprisesParser parser = new GrPepStateEnterprisesParser();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(GrPepStateEnterprisesParser.ID_PREFIX, GrPepStateEnterprisesParser.TOPIC);

        int count = parser.convert(RAW, dataset);
        assertTrue(count >= 5);
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertFalse(dataset.entities().isEmpty());

        boolean foundStassis = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Georgios Stassis"));
        assertTrue(foundStassis, "Georgios Stassis doit être présent dans le dataset");
    }
}
