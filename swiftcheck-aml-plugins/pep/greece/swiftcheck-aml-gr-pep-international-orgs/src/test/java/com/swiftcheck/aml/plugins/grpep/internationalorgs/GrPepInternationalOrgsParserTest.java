package com.swiftcheck.aml.plugins.grpep.internationalorgs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class GrPepInternationalOrgsParserTest {

    private static final Path RAW = Path.of("src/test/resources/gr-international-orgs-wikipedia.json");

    @Test
    void testConvert() throws Exception {
        GrPepInternationalOrgsParser parser = new GrPepInternationalOrgsParser();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(GrPepInternationalOrgsParser.ID_PREFIX, GrPepInternationalOrgsParser.TOPIC);

        int count = parser.convert(RAW, dataset);
        assertTrue(count >= 3, "Au moins 3 dirigeants attendus, obtenu : " + count);
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertFalse(dataset.entities().isEmpty());

        boolean foundLeader = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Juhan Lepassaar"));
        assertTrue(foundLeader, "Juhan Lepassaar doit être présent dans le dataset");
    }
}
