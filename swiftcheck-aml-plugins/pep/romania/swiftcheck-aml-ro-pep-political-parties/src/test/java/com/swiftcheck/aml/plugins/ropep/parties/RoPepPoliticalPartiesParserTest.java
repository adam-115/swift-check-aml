package com.swiftcheck.aml.plugins.ropep.parties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class RoPepPoliticalPartiesParserTest {

    private static final Path RAW = Path.of("src/test/resources/ro-political-parties-wikipedia.json");

    @Test
    void testConvert() throws Exception {
        RoPepPoliticalPartiesParser parser = new RoPepPoliticalPartiesParser();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(RoPepPoliticalPartiesParser.ID_PREFIX, RoPepPoliticalPartiesParser.TOPIC);

        int count = parser.convert(RAW, dataset);
        assertTrue(count >= 7, "Au moins 7 dirigeants des partis politiques roumains doivent être indexés, trouvé: " + count);
        assertEquals(0, dataset.qualityReport().getTotalRejected(), "Aucune entité rejetée");
        assertFalse(dataset.entities().isEmpty());

        boolean foundCiolacu = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Marcel Ciolacu"));
        assertTrue(foundCiolacu, "Marcel Ciolacu doit être présent dans le dataset");

        boolean foundCiuca = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Nicolae Ciucă"));
        assertTrue(foundCiuca, "Nicolae Ciucă doit être présent dans le dataset");
    }
}
