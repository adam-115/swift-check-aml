package com.swiftcheck.aml.plugins.plpep.senat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class PlPepSenatParserTest {

    private static final Path RAW = Path.of("src/test/resources/pl-senat-wikipedia.json");

    @Test
    void testConvert() throws Exception {
        PlPepSenatParser parser = new PlPepSenatParser();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder("pl-pep-senat", "role.pep");

        int count = parser.convert(RAW, dataset);
        assertTrue(count >= 95, "Au moins 95 sénateurs doivent être extraits, trouvé: " + count);
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertFalse(dataset.entities().isEmpty());

        boolean foundKidawa = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Małgorzata Kidawa-Błońska"));
        assertTrue(foundKidawa, "Małgorzata Kidawa-Błońska doit être présente dans le dataset");

        boolean foundBodnar = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Adam Bodnar"));
        assertTrue(foundBodnar, "Adam Bodnar doit être présent dans le dataset");

        boolean foundGrodzki = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Tomasz Grodzki"));
        assertTrue(foundGrodzki, "Tomasz Grodzki doit être présent dans le dataset");
    }
}
