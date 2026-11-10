package com.swiftcheck.aml.plugins.plpep.centralbank;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class PlPepCentralBankParserTest {

    private static final Path RAW = Path.of("src/main/resources/seed/pl-central-bank.json");

    @Test
    void testConvert() throws Exception {
        PlPepCentralBankParser parser = new PlPepCentralBankParser();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder("pl-pep-central-bank", "role.pep");

        int count = parser.convert(RAW, dataset);
        assertEquals(14, count);
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertFalse(dataset.entities().isEmpty());

        boolean foundGlapinski = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Adam Glapiński"));
        assertTrue(foundGlapinski, "Adam Glapiński doit être présent dans le dataset");
    }
}

