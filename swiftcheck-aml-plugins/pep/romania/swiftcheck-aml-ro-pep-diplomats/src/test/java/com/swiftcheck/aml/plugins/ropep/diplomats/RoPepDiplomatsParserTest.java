package com.swiftcheck.aml.plugins.ropep.diplomats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class RoPepDiplomatsParserTest {

    private static final Path RAW = Path.of("src/main/resources/seed/ro-diplomats.json");

    @Test
    void testConvert() throws Exception {
        RoPepDiplomatsParser parser = new RoPepDiplomatsParser();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder("ro-pep-diplomats", "role.pep");

        int count = parser.convert(RAW, dataset);
        assertEquals(10, count);
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertFalse(dataset.entities().isEmpty());

        boolean foundMuraru = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Andrei Muraru"));
        assertTrue(foundMuraru, "Andrei Muraru doit être présent dans le dataset");
    }
}

