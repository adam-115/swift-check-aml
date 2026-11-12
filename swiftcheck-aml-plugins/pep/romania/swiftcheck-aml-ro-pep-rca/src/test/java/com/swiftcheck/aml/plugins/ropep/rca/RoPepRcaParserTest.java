package com.swiftcheck.aml.plugins.ropep.rca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class RoPepRcaParserTest {

    private static final Path RAW = Path.of("src/test/resources/ro-rca-wikipedia.json");

    @Test
    void testConvert() throws Exception {
        RoPepRcaParser parser = new RoPepRcaParser();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder("ro-pep-rca", "role.rca");

        int count = parser.convert(RAW, dataset);
        assertTrue(count >= 9, "Au moins 9 proches ou associés doivent être indexés, trouvé: " + count);
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertFalse(dataset.entities().isEmpty());

        boolean foundCarmen = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Carmen Iohannis"));
        assertTrue(foundCarmen, "Carmen Iohannis doit être présente dans le dataset");

        boolean hasFamily = dataset.entities().stream().anyMatch(e -> "Family".equals(e.getSchema()));
        assertTrue(hasFamily, "Le schéma Family doit être présent dans le dataset");

        boolean hasAssociate = dataset.entities().stream().anyMatch(e -> "Associate".equals(e.getSchema()));
        assertTrue(hasAssociate, "Le schéma Associate doit être présent dans le dataset");
    }
}
