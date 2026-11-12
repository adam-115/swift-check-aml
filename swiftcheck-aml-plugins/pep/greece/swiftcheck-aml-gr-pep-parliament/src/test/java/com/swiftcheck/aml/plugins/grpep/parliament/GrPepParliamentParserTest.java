package com.swiftcheck.aml.plugins.grpep.parliament;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class GrPepParliamentParserTest {

    private static final Path RAW = Path.of("src/test/resources/gr-parliament-wikipedia.json");

    @Test
    void testConvert() throws Exception {
        GrPepParliamentParser parser = new GrPepParliamentParser();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(GrPepParliamentParser.ID_PREFIX, GrPepParliamentParser.TOPIC);

        int count = parser.convert(RAW, dataset);
        assertTrue(count >= 50, "Au moins 50 députés attendus, obtenu : " + count);
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertFalse(dataset.entities().isEmpty());

        boolean foundTasoulas = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Konstantinos Tasoulas"));
        assertTrue(foundTasoulas, "Konstantinos Tasoulas doit être présent dans le dataset");
    }
}
