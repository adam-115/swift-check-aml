package com.swiftcheck.aml.plugins.plpep.president;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class PlPepPresidentParserTest {

    private static final Path RAW = Path.of("src/test/resources/pl-president-wikipedia.json");

    @Test
    void testConvert() throws Exception {
        PlPepPresidentParser parser = new PlPepPresidentParser();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder("pl-pep-president", "role.pep");

        int count = parser.convert(RAW, dataset);
        assertTrue(count >= 10, "Au moins 10 dirigeants doivent être extraits, trouvé: " + count);
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertFalse(dataset.entities().isEmpty());

        boolean foundDuda = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Andrzej Duda"));
        assertTrue(foundDuda, "Andrzej Duda doit être présent dans le dataset");

        boolean foundPaprocka = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Małgorzata Paprocka"));
        assertTrue(foundPaprocka, "Małgorzata Paprocka doit être présente dans le dataset");

        boolean foundSiewiera = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Jacek Siewiera"));
        assertTrue(foundSiewiera, "Jacek Siewiera doit être présent dans le dataset");
    }
}
