package com.swiftcheck.aml.plugins.ropep.army;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class RoPepArmedForcesParserTest {

    private static final Path RAW = Path.of("src/main/resources/seed/ro-armed-forces.json");

    @Test
    void testConvert() throws Exception {
        RoPepArmedForcesParser parser = new RoPepArmedForcesParser();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder("ro-pep-army", "role.pep");

        int count = parser.convert(RAW, dataset);
        assertEquals(8, count);
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertFalse(dataset.entities().isEmpty());

        boolean foundVlad = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Gheorghiță Vlad"));
        assertTrue(foundVlad, "Gheorghiță Vlad doit être présent dans le dataset");
    }
}

