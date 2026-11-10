package com.swiftcheck.aml.plugins.plpep.rca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class PlPepRcaParserTest {

    private static final Path RAW = Path.of("src/main/resources/seed/pl-rca.json");

    @Test
    void testConvert() throws Exception {
        PlPepRcaParser parser = new PlPepRcaParser();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder("pl-pep-rca", "role.rca");

        int count = parser.convert(RAW, dataset);
        assertEquals(10, count);
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertFalse(dataset.entities().isEmpty());

        boolean foundAgata = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Agata Kornhauser-Duda"));
        assertTrue(foundAgata, "Agata Kornhauser-Duda doit être présente dans le dataset");

        boolean hasFamily = dataset.entities().stream().anyMatch(e -> "Family".equals(e.getSchema()));
        boolean hasAssociate = dataset.entities().stream().anyMatch(e -> "Associate".equals(e.getSchema()));
        assertTrue(hasFamily, "Des arêtes Family doivent exister");
        assertTrue(hasAssociate, "Des arêtes Associate doivent exister");
    }
}

