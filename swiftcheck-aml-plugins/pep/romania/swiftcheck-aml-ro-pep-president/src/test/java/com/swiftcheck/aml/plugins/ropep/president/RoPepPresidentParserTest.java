package com.swiftcheck.aml.plugins.ropep.president;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class RoPepPresidentParserTest {

    private static final Path RAW = Path.of("src/test/resources/ro-presidents-wiki.json");

    @Test
    void testConvert() throws Exception {
        RoPepPresidentParser parser = new RoPepPresidentParser();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder("ro-pep-president", "role.pep");

        int count = parser.convert(RAW, dataset);
        assertTrue(count >= 4, "Au moins 4 présidents roumains post-1989 doivent être extraits, trouvé: " + count);
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertFalse(dataset.entities().isEmpty());

        boolean foundIohannis = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Klaus Iohannis"));
        assertTrue(foundIohannis, "Klaus Iohannis doit être présent dans le dataset");

        boolean foundBasescu = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Traian Băsescu"));
        assertTrue(foundBasescu, "Traian Băsescu doit être présent dans le dataset");

        boolean foundIliescu = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Ion Iliescu"));
        assertTrue(foundIliescu, "Ion Iliescu doit être présent dans le dataset");
    }
}
