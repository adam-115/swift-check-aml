package com.swiftcheck.aml.plugins.ropep.deputies;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class RoPepDeputiesParserTest {

    private static final Path RAW = Path.of("src/main/resources/seed/ro-deputies.json");

    @Test
    void testConvert() throws Exception {
        RoPepDeputiesParser parser = new RoPepDeputiesParser();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder("ro-pep-deputies", "role.pep");

        int count = parser.convert(RAW, dataset);
        assertEquals(15, count);
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertFalse(dataset.entities().isEmpty());

        boolean foundSuciu = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Daniel Suciu"));
        assertTrue(foundSuciu, "Daniel Suciu doit être présent dans le dataset");

        boolean foundBode = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Lucian-Nicolae Bode"));
        assertTrue(foundBode, "Lucian-Nicolae Bode doit être présent dans le dataset");
    }
}

