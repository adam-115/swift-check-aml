package com.swiftcheck.aml.plugins.ropep.orgs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class RoPepInternationalOrgsParserTest {

    private static final Path RAW = Path.of("src/main/resources/seed/ro-international-orgs.json");

    @Test
    void testConvert() throws Exception {
        RoPepInternationalOrgsParser parser = new RoPepInternationalOrgsParser();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder("ro-pep-orgs", "role.pep");

        int count = parser.convert(RAW, dataset);
        assertEquals(6, count);
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertFalse(dataset.entities().isEmpty());

        boolean foundTagliaretti = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Luca Tagliaretti"));
        assertTrue(foundTagliaretti, "Luca Tagliaretti doit être présent dans le dataset");

        boolean foundKovesi = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Laura Codruța Kövesi"));
        assertTrue(foundKovesi, "Laura Codruța Kövesi doit être présente dans le dataset");
    }
}

