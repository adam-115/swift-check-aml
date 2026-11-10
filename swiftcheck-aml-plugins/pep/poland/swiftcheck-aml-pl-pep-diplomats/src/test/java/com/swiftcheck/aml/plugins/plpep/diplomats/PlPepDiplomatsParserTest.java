package com.swiftcheck.aml.plugins.plpep.diplomats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class PlPepDiplomatsParserTest {

    private static final Path RAW = Path.of("src/main/resources/seed/pl-diplomats.json");

    @Test
    void testConvert() throws Exception {
        PlPepDiplomatsParser parser = new PlPepDiplomatsParser();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder("pl-pep-diplomats", "role.pep");

        int count = parser.convert(RAW, dataset);
        assertEquals(12, count);
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertFalse(dataset.entities().isEmpty());

        boolean foundWilczek = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Piotr Wilczek"));
        assertTrue(foundWilczek, "Piotr Wilczek doit être présent dans le dataset");
    }
}

