package com.swiftcheck.aml.plugins.ropep.curteaconturi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class RoPepCourtOfAccountsParserTest {

    private static final Path RAW = Path.of("src/test/resources/ro-court-of-accounts-wikipedia.json");

    @Test
    void testConvert() throws Exception {
        RoPepCourtOfAccountsParser parser = new RoPepCourtOfAccountsParser();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(RoPepCourtOfAccountsParser.ID_PREFIX, RoPepCourtOfAccountsParser.TOPIC);

        int count = parser.convert(RAW, dataset);
        assertTrue(count >= 5, "Au moins 5 dirigeants de la Cour des Comptes doivent être indexés, trouvé: " + count);
        assertEquals(0, dataset.qualityReport().getTotalRejected(), "Aucune entité rejetée");
        assertFalse(dataset.entities().isEmpty());

        boolean foundBusuioc = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Mihai Busuioc"));
        assertTrue(foundBusuioc, "Mihai Busuioc doit être présent dans le dataset");

        boolean foundCalugareanu = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Mirela Călugăreanu"));
        assertTrue(foundCalugareanu, "Mirela Călugăreanu doit être présente dans le dataset");
    }
}
