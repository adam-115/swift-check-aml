package com.swiftcheck.aml.plugins.plpep.rca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class PlPepRcaParserTest {

    private static final Path RAW = Path.of("src/test/resources/pl-rca-sparql.json");

    @Test
    void testConvert() throws Exception {
        PlPepRcaParser parser = new PlPepRcaParser();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder("pl-pep-rca", "role.rca");

        int count = parser.convert(RAW, dataset);
        assertTrue(count >= 10, "Au moins 10 membres RCA doivent être extraits, trouvé: " + count);
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertFalse(dataset.entities().isEmpty());

        boolean foundAgata = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Agata Kornhauser-Duda"));
        assertTrue(foundAgata, "Agata Kornhauser-Duda doit être présente dans le dataset");

        boolean foundMalgorzata = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Małgorzata Tusk"));
        assertTrue(foundMalgorzata, "Małgorzata Tusk doit être présente dans le dataset");

        boolean foundKinga = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Kinga Duda"));
        assertTrue(foundKinga, "Kinga Duda doit être présente dans le dataset");

        boolean foundFamilyEdge = dataset.entities().stream()
                .anyMatch(e -> "Family".equals(e.getSchema()));
        assertTrue(foundFamilyEdge, "Au moins une relation Family doit être créée");
    }
}
