package com.swiftcheck.aml.plugins.plpep.constitutionaltribunal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class PlPepConstitutionalTribunalParserTest {

    private static final Path RAW = Path.of("src/main/resources/seed/pl-constitutional-tribunal.json");

    @Test
    void testConvert() throws Exception {
        PlPepConstitutionalTribunalParser parser = new PlPepConstitutionalTribunalParser();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder("pl-pep-constitutional-tribunal", "role.pep");

        int count = parser.convert(RAW, dataset);
        assertEquals(13, count);
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertFalse(dataset.entities().isEmpty());

        boolean foundPrzylebska = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Julia Przyłębska"));
        assertTrue(foundPrzylebska, "Julia Przyłębska doit être présente dans le dataset");
    }
}

