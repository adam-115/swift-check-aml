package com.swiftcheck.aml.plugins.grpep.supremecourts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class GrPepSupremeCourtsParserTest {

    private static final Path RAW = Path.of("src/test/resources/gr-supreme-courts-wikipedia.json");

    @Test
    void testConvert() throws Exception {
        GrPepSupremeCourtsParser parser = new GrPepSupremeCourtsParser();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(GrPepSupremeCourtsParser.ID_PREFIX, GrPepSupremeCourtsParser.TOPIC);

        int count = parser.convert(RAW, dataset);
        assertTrue(count >= 5, "Au moins 5 hauts magistrats doivent être indexés, trouvé: " + count);
        assertEquals(0, dataset.qualityReport().getTotalRejected(), "Aucune entité ne doit être rejetée");
        assertFalse(dataset.entities().isEmpty());

        boolean foundKlapa = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Ioanna Klapa-Christodoulea"));
        assertTrue(foundKlapa, "Ioanna Klapa-Christodoulea doit être présente dans le dataset");

        boolean foundNika = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Evangelia Nika"));
        assertTrue(foundNika, "Evangelia Nika doit être présente dans le dataset");
    }
}
