package com.swiftcheck.aml.plugins.plpep.centralbank;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class PlPepCentralBankParserTest {

    private static final Path RAW = Path.of("src/test/resources/pl-central-bank-wikipedia.json");

    @Test
    void testConvert() throws Exception {
        PlPepCentralBankParser parser = new PlPepCentralBankParser();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(
                PlPepCentralBankParser.ID_PREFIX,
                PlPepCentralBankParser.TOPIC
        );

        int count = parser.convert(RAW, dataset);
        assertTrue(count >= 14, "Au moins 14 dirigeants NBP & RPP attendus, trouvé: " + count);
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertFalse(dataset.entities().isEmpty());

        boolean foundGlapinski = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Adam Glapiński"));
        assertTrue(foundGlapinski, "Adam Glapiński doit être présent dans le dataset");

        boolean foundKightley = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Marta Kightley"));
        assertTrue(foundKightley, "Marta Kightley doit être présente dans le dataset");

        boolean foundTyrowicz = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Joanna Tyrowicz"));
        assertTrue(foundTyrowicz, "Joanna Tyrowicz doit être présente dans le dataset");
    }
}
