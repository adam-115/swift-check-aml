package com.swiftcheck.aml.plugins.plpep.parties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class PlPepPoliticalPartiesParserTest {

    private static final Path RAW = Path.of("src/test/resources/pl-political-parties-sparql.json");

    @Test
    void testConvert() throws Exception {
        PlPepPoliticalPartiesParser parser = new PlPepPoliticalPartiesParser();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(
                PlPepPoliticalPartiesParser.ID_PREFIX,
                PlPepPoliticalPartiesParser.TOPIC
        );

        int count = parser.convert(RAW, dataset);
        assertTrue(count >= 10, "Au moins 10 dirigeants politiques attendus, trouvé: " + count);
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

        boolean foundHolownia = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Szymon Hołownia"));
        assertTrue(foundHolownia, "Szymon Hołownia doit être présent dans le dataset");

        boolean foundBosak = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Krzysztof Bosak"));
        assertTrue(foundBosak, "Krzysztof Bosak doit être présent dans le dataset");
    }
}
