package com.swiftcheck.aml.plugins.plpep.enterprises;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class PlPepStateEnterprisesParserTest {

    private static final Path RAW = Path.of("src/test/resources/pl-state-enterprises-wikipedia.json");

    @Test
    void testConvert() throws Exception {
        PlPepStateEnterprisesParser parser = new PlPepStateEnterprisesParser();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder("pl-pep-state-enterprises", "role.pep");

        int count = parser.convert(RAW, dataset);
        assertTrue(count >= 10, "Au moins 10 dirigeants d'entreprises doivent être extraits, trouvé: " + count);
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertFalse(dataset.entities().isEmpty());

        boolean foundFafara = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Ireneusz Fąfara"));
        assertTrue(foundFafara, "Ireneusz Fąfara doit être présent dans le dataset");

        boolean foundBeroud = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Alan Beroud"));
        assertTrue(foundBeroud, "Alan Beroud doit être présent dans le dataset");
    }
}
