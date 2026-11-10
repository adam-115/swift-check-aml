package com.swiftcheck.aml.plugins.plpep.auditcourt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class PlPepAuditCourtParserTest {

    private static final Path RAW = Path.of("src/main/resources/seed/pl-audit-court.json");

    @Test
    void testConvert() throws Exception {
        PlPepAuditCourtParser parser = new PlPepAuditCourtParser();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder("pl-pep-audit-court", "role.pep");

        int count = parser.convert(RAW, dataset);
        assertEquals(8, count);
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertFalse(dataset.entities().isEmpty());

        boolean foundBanas = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Marian Banaś"));
        assertTrue(foundBanas, "Marian Banaś doit être présent dans le dataset");
    }
}

