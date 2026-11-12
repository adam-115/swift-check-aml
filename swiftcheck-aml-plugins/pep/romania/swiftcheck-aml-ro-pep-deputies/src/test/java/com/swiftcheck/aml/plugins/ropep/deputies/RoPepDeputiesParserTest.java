package com.swiftcheck.aml.plugins.ropep.deputies;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class RoPepDeputiesParserTest {

    private static final Path RAW = Path.of("src/test/resources/ro-deputies-wikipedia.json");

    @Test
    void testConvert() throws Exception {
        RoPepDeputiesParser parser = new RoPepDeputiesParser();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(RoPepDeputiesParser.ID_PREFIX, RoPepDeputiesParser.TOPIC);

        int count = parser.convert(RAW, dataset);
        assertTrue(count >= 300, "Au moins 300 députés roumains doivent être indexés, trouvé: " + count);
        assertEquals(0, dataset.qualityReport().getTotalRejected(), "Aucune entité rejetée");
        assertFalse(dataset.entities().isEmpty());

        boolean foundSuciu = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Vasile-Daniel Suciu") || e.getValues("alias").contains("Daniel Suciu"));
        assertTrue(foundSuciu, "Daniel Suciu doit être présent dans le dataset");

        boolean foundBode = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Lucian Nicolae Bode") || e.getValues("alias").contains("Lucian-Nicolae Bode"));
        assertTrue(foundBode, "Lucian-Nicolae Bode doit être présent dans le dataset");
    }
}
