package com.swiftcheck.aml.plugins.plpep.auditcourt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class PlPepAuditCourtParserTest {

    private static final Path RAW = Path.of("src/test/resources/nik-kolegium.html");

    @Test
    void testConvert() throws Exception {
        PlPepAuditCourtParser parser = new PlPepAuditCourtParser();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder("pl-pep-audit-court", "role.pep");

        int count = parser.convert(RAW, dataset);
        assertEquals(19, count);
        assertEquals(0, dataset.qualityReport().getTotalRejected(), "Aucune valeur rejetée ne doit être présente");
        assertFalse(dataset.entities().isEmpty());

        boolean foundHaladyj = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Mariusz Haładyj"));
        assertTrue(foundHaladyj, "Mariusz Haładyj (nouveau Prezes NIK) doit être présent dans le dataset");

        boolean foundBudnikowska = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .anyMatch(e -> e.getValues("name").contains("Katarzyna Budnikowska"));
        assertTrue(foundBudnikowska, "Katarzyna Budnikowska (Wiceprezes NIK) doit être présente");
    }
}
