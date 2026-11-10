package com.swiftcheck.aml.plugins.plpep.orgs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class PlPepInternationalOrgsParserTest {

    private static final Path RAW = Path.of("src/main/resources/seed/pl-international-orgs.json");

    @Test
    void testConvert() throws Exception {
        PlPepInternationalOrgsParser parser = new PlPepInternationalOrgsParser();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder("pl-pep-international-orgs", "role.pep");

        int count = parser.convert(RAW, dataset);
        assertEquals(6, count);
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertFalse(dataset.entities().isEmpty());

        boolean foundLeijtens = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Hans Leijtens"));
        assertTrue(foundLeijtens, "Hans Leijtens doit être présent dans le dataset");
    }
}

