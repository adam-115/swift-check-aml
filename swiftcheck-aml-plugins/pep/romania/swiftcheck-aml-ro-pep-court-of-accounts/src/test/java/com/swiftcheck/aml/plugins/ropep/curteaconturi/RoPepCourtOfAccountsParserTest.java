package com.swiftcheck.aml.plugins.ropep.curteaconturi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class RoPepCourtOfAccountsParserTest {

    private static final Path RAW = Path.of("src/main/resources/seed/ro-court-of-accounts.json");

    @Test
    void testConvert() throws Exception {
        RoPepCourtOfAccountsParser parser = new RoPepCourtOfAccountsParser();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder("ro-pep-court-accounts", "role.pep");

        int count = parser.convert(RAW, dataset);
        assertEquals(9, count);
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertFalse(dataset.entities().isEmpty());

        boolean foundBusuioc = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Mihai Busuioc"));
        assertTrue(foundBusuioc, "Mihai Busuioc doit être présent dans le dataset");
    }
}

