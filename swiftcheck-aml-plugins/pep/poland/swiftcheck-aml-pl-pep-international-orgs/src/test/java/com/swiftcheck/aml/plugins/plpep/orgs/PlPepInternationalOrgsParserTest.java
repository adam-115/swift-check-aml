package com.swiftcheck.aml.plugins.plpep.orgs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class PlPepInternationalOrgsParserTest {

    private static final Path RAW = Path.of("src/test/resources/pl-international-orgs-wikipedia.json");

    @Test
    void testConvert() throws Exception {
        PlPepInternationalOrgsParser parser = new PlPepInternationalOrgsParser();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(
                PlPepInternationalOrgsParser.ID_PREFIX,
                PlPepInternationalOrgsParser.TOPIC
        );

        int count = parser.convert(RAW, dataset);
        assertTrue(count >= 5, "Au moins 5 dirigeants attendus, trouvé: " + count);
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertFalse(dataset.entities().isEmpty());

        boolean foundLeijtens = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Hans Leijtens"));
        assertTrue(foundLeijtens, "Hans Leijtens doit être présent dans le dataset");

        boolean foundKalnaja = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Aija Kalnaja"));
        assertTrue(foundKalnaja, "Aija Kalnaja doit être présente dans le dataset");

        boolean foundGerdes = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Lars Gerdes"));
        assertTrue(foundGerdes, "Lars Gerdes doit être présent dans le dataset");
    }
}
