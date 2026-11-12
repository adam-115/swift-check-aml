package com.swiftcheck.aml.plugins.ropep.orgs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class RoPepInternationalOrgsParserTest {

    private static final Path WIKI = Path.of("src/test/resources/ro-international-orgs-wikipedia.json");
    private static final Path ECCC = Path.of("src/test/resources/ro-eccc-governance.html");

    @Test
    void testConvert() throws Exception {
        RoPepInternationalOrgsParser parser = new RoPepInternationalOrgsParser();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(RoPepInternationalOrgsParser.ID_PREFIX, RoPepInternationalOrgsParser.TOPIC);

        int count = parser.convert(WIKI, ECCC, dataset);
        assertTrue(count >= 5, "Au moins 5 dirigeants d'organisations internationales doivent être indexés, trouvé: " + count);
        assertEquals(0, dataset.qualityReport().getTotalRejected(), "Aucune entité rejetée");
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
