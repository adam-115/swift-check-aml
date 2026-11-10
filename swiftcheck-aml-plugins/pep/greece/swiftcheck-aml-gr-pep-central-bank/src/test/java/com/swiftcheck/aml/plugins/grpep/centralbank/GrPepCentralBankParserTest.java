package com.swiftcheck.aml.plugins.grpep.centralbank;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class GrPepCentralBankParserTest {

    private static final Path RAW = Path.of("src/main/resources/seed/gr-central-bank.json");

    @Test
    void testConvert() throws Exception {
        GrPepCentralBankParser parser = new GrPepCentralBankParser();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(GrPepCentralBankParser.ID_PREFIX, GrPepCentralBankParser.TOPIC);

        int count = parser.convert(RAW, dataset);
        assertTrue(count >= 4);
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertFalse(dataset.entities().isEmpty());

        boolean foundGovernor = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Yannis Stournaras"));
        assertTrue(foundGovernor, "Yannis Stournaras doit être présent dans le dataset");
    }
}
