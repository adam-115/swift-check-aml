package com.swiftcheck.aml.plugins.ropep.iccj;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class RoPepSupremeCourtParserTest {

    private static final Path RAW = Path.of("src/main/resources/seed/ro-supreme-court.json");

    @Test
    void testConvert() throws Exception {
        RoPepSupremeCourtParser parser = new RoPepSupremeCourtParser();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder("ro-pep-iccj", "role.pep");

        int count = parser.convert(RAW, dataset);
        assertEquals(8, count);
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertFalse(dataset.entities().isEmpty());

        boolean foundCorbu = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Corina-Alina Corbu"));
        assertTrue(foundCorbu, "Corina-Alina Corbu doit être présente dans le dataset");
    }
}

