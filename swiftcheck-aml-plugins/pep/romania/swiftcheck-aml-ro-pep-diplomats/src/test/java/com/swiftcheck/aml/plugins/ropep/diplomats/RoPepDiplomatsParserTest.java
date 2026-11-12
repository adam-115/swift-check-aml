package com.swiftcheck.aml.plugins.ropep.diplomats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class RoPepDiplomatsParserTest {

    private static final Path RAW = Path.of("src/test/resources/ro-diplomats-sparql.json");

    @Test
    void testConvert() throws Exception {
        RoPepDiplomatsParser parser = new RoPepDiplomatsParser();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(RoPepDiplomatsParser.ID_PREFIX, RoPepDiplomatsParser.TOPIC);

        int count = parser.convert(RAW, dataset);
        assertTrue(count >= 10, "Au moins 10 diplomates roumains doivent être indexés, trouvé: " + count);
        assertEquals(0, dataset.qualityReport().getTotalRejected(), "Aucune entité rejetée");
        assertFalse(dataset.entities().isEmpty());

        boolean foundMuraru = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Andrei Muraru"));
        assertTrue(foundMuraru, "Andrei Muraru doit être présent dans le dataset");

        boolean foundHurezeanu = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Emil Hurezeanu"));
        assertTrue(foundHurezeanu, "Emil Hurezeanu doit être présent dans le dataset");
    }
}
