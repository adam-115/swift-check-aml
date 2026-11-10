package com.swiftcheck.aml.plugins.ropep.ccr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class RoPepConstitutionalCourtParserTest {

    private static final Path RAW = Path.of("src/main/resources/seed/ro-constitutional-court.json");

    @Test
    void testConvert() throws Exception {
        RoPepConstitutionalCourtParser parser = new RoPepConstitutionalCourtParser();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder("ro-pep-ccr", "role.pep");

        int count = parser.convert(RAW, dataset);
        assertEquals(9, count);
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertFalse(dataset.entities().isEmpty());

        boolean foundEnache = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Marian Enache"));
        assertTrue(foundEnache, "Marian Enache doit être présent dans le dataset");
    }
}

