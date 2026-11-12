package com.swiftcheck.aml.plugins.grpep.centralbank;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class GrPepCentralBankParserTest {

    private static final Path RAW = Path.of("src/test/resources/gr-central-bank-sparql.json");

    @Test
    void testConvert() throws Exception {
        GrPepCentralBankParser parser = new GrPepCentralBankParser();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(GrPepCentralBankParser.ID_PREFIX, GrPepCentralBankParser.TOPIC);

        int count = parser.convert(RAW, dataset);
        assertTrue(count >= 10, "Au moins 10 membres doivent être extraits, trouvé: " + count);
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertFalse(dataset.entities().isEmpty());

        boolean foundGovernor = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Yannis Stournaras") || e.getValues("name").contains("Γιάννης Στουρνάρας"));
        assertTrue(foundGovernor, "Yannis Stournaras doit être présent dans le dataset");

        boolean foundPapakonstantinou = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Christina Papakonstantinou") || e.getValues("name").contains("Χριστίνα Παπακωνσταντίνου"));
        assertTrue(foundPapakonstantinou, "Christina Papakonstantinou doit être présente dans le dataset");
    }
}
