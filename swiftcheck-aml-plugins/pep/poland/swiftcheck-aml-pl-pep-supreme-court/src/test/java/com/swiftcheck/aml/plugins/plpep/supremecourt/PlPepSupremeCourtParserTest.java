package com.swiftcheck.aml.plugins.plpep.supremecourt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class PlPepSupremeCourtParserTest {

    private static final Path RAW = Path.of("src/main/resources/seed/pl-supreme-court.json");

    @Test
    void testConvert() throws Exception {
        PlPepSupremeCourtParser parser = new PlPepSupremeCourtParser();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder("pl-pep-supreme-court", "role.pep");

        int count = parser.convert(RAW, dataset);
        assertEquals(12, count);
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertFalse(dataset.entities().isEmpty());

        boolean foundManowska = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Małgorzata Manowska"));
        assertTrue(foundManowska, "Małgorzata Manowska doit être présente dans le dataset");

        boolean foundChlebny = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Jacek Chlebny"));
        assertTrue(foundChlebny, "Jacek Chlebny doit être présent dans le dataset");
    }
}

