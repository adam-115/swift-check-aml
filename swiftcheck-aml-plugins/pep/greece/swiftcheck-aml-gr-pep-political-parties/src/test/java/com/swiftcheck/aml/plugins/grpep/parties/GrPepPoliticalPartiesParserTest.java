package com.swiftcheck.aml.plugins.grpep.parties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class GrPepPoliticalPartiesParserTest {

    private static final Path RAW = Path.of("src/test/resources/gr-political-parties-wikipedia.json");

    @Test
    void testConvert() throws Exception {
        GrPepPoliticalPartiesParser parser = new GrPepPoliticalPartiesParser();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(GrPepPoliticalPartiesParser.ID_PREFIX, GrPepPoliticalPartiesParser.TOPIC);

        int count = parser.convert(RAW, dataset);
        assertTrue(count >= 7, "Au moins 7 dirigeants attendus, obtenu : " + count);
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertFalse(dataset.entities().isEmpty());

        boolean foundOfficial = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Maria Syrreggela") || e.getValues("name").contains("Maria Syrengela"));
        assertTrue(foundOfficial, "Maria Syrreggela doit être présente dans le dataset");
    }
}
