package com.swiftcheck.aml.plugins.plpep.constitutionaltribunal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class PlPepConstitutionalTribunalParserTest {

    private static final Path RAW = Path.of("src/test/resources/pl-constitutional-tribunal-wikipedia.json");

    @Test
    void testConvert() throws Exception {
        PlPepConstitutionalTribunalParser parser = new PlPepConstitutionalTribunalParser();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(
                PlPepConstitutionalTribunalParser.ID_PREFIX,
                PlPepConstitutionalTribunalParser.TOPIC
        );

        int count = parser.convert(RAW, dataset);
        assertTrue(count >= 13, "Au moins 13 juges constitutionnels attendus, trouvé: " + count);
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertFalse(dataset.entities().isEmpty());

        boolean foundPrzylebska = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Julia Przyłębska"));
        assertTrue(foundPrzylebska, "Julia Przyłębska doit être présente dans le dataset");

        boolean foundSwieczkowski = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Bogdan Święczkowski"));
        assertTrue(foundSwieczkowski, "Bogdan Święczkowski doit être présent dans le dataset");

        boolean foundPawlowicz = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Krystyna Pawłowicz"));
        assertTrue(foundPawlowicz, "Krystyna Pawłowicz doit être présente dans le dataset");
    }
}
