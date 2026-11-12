package com.swiftcheck.aml.plugins.ropep.ccr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class RoPepConstitutionalCourtParserTest {

    private static final Path RAW = Path.of("src/test/resources/ro-constitutional-court-wikipedia.json");

    @Test
    void testConvert() throws Exception {
        RoPepConstitutionalCourtParser parser = new RoPepConstitutionalCourtParser();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(RoPepConstitutionalCourtParser.ID_PREFIX, RoPepConstitutionalCourtParser.TOPIC);

        int count = parser.convert(RAW, dataset);
        assertTrue(count >= 9, "Au moins 9 juges de la CCR doivent être indexés, trouvé: " + count);
        assertEquals(0, dataset.qualityReport().getTotalRejected(), "Aucune entité rejetée");
        assertFalse(dataset.entities().isEmpty());

        boolean foundEnache = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Marian Enache"));
        assertTrue(foundEnache, "Marian Enache doit être présent dans le dataset");

        boolean foundTanasescu = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Elena Simina Tănăsescu"));
        assertTrue(foundTanasescu, "Elena Simina Tănăsescu doit être présente dans le dataset");
    }
}
