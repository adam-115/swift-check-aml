package com.swiftcheck.aml.plugins.plpep.parties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class PlPepPoliticalPartiesParserTest {

    private static final Path RAW = Path.of("src/main/resources/seed/pl-political-parties.json");

    @Test
    void testConvert() throws Exception {
        PlPepPoliticalPartiesParser parser = new PlPepPoliticalPartiesParser();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder("pl-pep-political-parties", "role.pep");

        int count = parser.convert(RAW, dataset);
        assertEquals(15, count);
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertFalse(dataset.entities().isEmpty());

        boolean foundTusk = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Donald Tusk"));
        assertTrue(foundTusk, "Donald Tusk doit être présent dans le dataset");

        boolean foundKaczynski = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Jarosław Kaczyński"));
        assertTrue(foundKaczynski, "Jarosław Kaczyński doit être présent dans le dataset");
    }
}
