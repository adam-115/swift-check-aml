package com.swiftcheck.aml.plugins.plpep.diplomats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class PlPepDiplomatsParserTest {

    private static final Path RAW = Path.of("src/test/resources/pl-ambasady.html");

    @Test
    void testConvert() throws Exception {
        PlPepDiplomatsParser parser = new PlPepDiplomatsParser();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder("pl-pep-diplomats", "role.pep");

        int count = parser.convert(RAW, dataset);
        assertEquals(134, count, "134 ambassadeurs et représentants officiels polonais dédupliqués");
        assertEquals(0, dataset.qualityReport().getTotalRejected(), "Aucune valeur rejetée ne doit être présente");
        assertFalse(dataset.entities().isEmpty());

        boolean foundRostek = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Robert Rostek"));
        assertTrue(foundRostek, "Robert Rostek (Ambassadeur en Arabie Saoudite) doit être présent dans le dataset");

        boolean foundNajder = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Jacek Najder"));
        assertTrue(foundNajder, "Jacek Najder (Représentant permanent auprès de l'OTAN) doit être présent dans le dataset");
    }
}
