package com.swiftcheck.aml.plugins.ropep.bnr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class RoPepCentralBankParserTest {

    private static final Path RAW = Path.of("src/main/resources/seed/ro-central-bank.json");

    @Test
    void testConvert() throws Exception {
        RoPepCentralBankParser parser = new RoPepCentralBankParser();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder("ro-pep-bnr", "role.pep");

        int count = parser.convert(RAW, dataset);
        assertEquals(9, count);
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertFalse(dataset.entities().isEmpty());

        boolean foundIsarescu = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Mugur Isărescu"));
        assertTrue(foundIsarescu, "Mugur Isărescu doit être présent dans le dataset");
    }
}

