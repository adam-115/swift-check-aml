package com.swiftcheck.aml.plugins.ropep.enterprises;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class RoPepStateEnterprisesParserTest {

    private static final Path RAW = Path.of("src/test/resources/ro-state-enterprises-wikipedia.json");

    @Test
    void testConvert() throws Exception {
        RoPepStateEnterprisesParser parser = new RoPepStateEnterprisesParser();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder("ro-pep-enterprises", "role.pep");

        int count = parser.convert(RAW, dataset);
        assertTrue(count >= 8, "Au moins 8 dirigeants d'entreprises publiques doivent être indexés, trouvé: " + count);
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertFalse(dataset.entities().isEmpty());

        boolean foundBorbely = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Karoly Borbely"));
        assertTrue(foundBorbely, "Karoly Borbely doit être présent dans le dataset");

        boolean foundSterian = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Ion Sterian"));
        assertTrue(foundSterian, "Ion Sterian doit être présent dans le dataset");
    }
}
