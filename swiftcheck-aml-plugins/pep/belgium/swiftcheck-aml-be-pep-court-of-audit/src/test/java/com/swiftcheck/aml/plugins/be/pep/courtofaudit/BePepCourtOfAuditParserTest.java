package com.swiftcheck.aml.plugins.be.pep.courtofaudit;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests unitaires hors-ligne de {@link BePepCourtOfAuditParser} sur la page
 * officielle {@code ccrek.be/fr/about-page/organisation}.
 */
class BePepCourtOfAuditParserTest {

    @Test
    void parsesAllTenMagistratesAndExcludesClerks(@TempDir Path tempDir) throws IOException {
        Path htmlFile = tempDir.resolve(BePepCourtOfAuditParser.FILE_NAME);
        copyResource("court-of-audit-organisation.html", htmlFile);

        BePepCourtOfAuditParser parser = new BePepCourtOfAuditParser();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(
                BePepCourtOfAuditParser.ID_PREFIX, BePepCourtOfAuditParser.TOPIC);

        List<BePepCourtOfAuditParser.MagistrateRecord> records = parser.convert(htmlFile, dataset);
        assertEquals(10, records.size(), "2 présidentes + 8 conseillers = 10 magistrats attendus");

        assertTrue(records.stream().anyMatch(r ->
                "Hilde François".equals(r.fullName())
                && "Premier président de la Cour des comptes".equals(r.roleLabel())
                && "Chambre néerlandaise".equals(r.chamber())));

        assertTrue(records.stream().anyMatch(r ->
                "Florence Thys".equals(r.fullName())
                && "Président de la Cour des comptes".equals(r.roleLabel())
                && "Chambre française".equals(r.chamber())));

        assertTrue(records.stream().anyMatch(r ->
                "Pierre Rion".equals(r.fullName())
                && "Conseiller à la Cour des comptes".equals(r.roleLabel())));

        assertFalse(records.stream().anyMatch(r -> r.fullName().contains("Bolly")));
        assertFalse(records.stream().anyMatch(r -> r.fullName().contains("Debusschere")));

        List<FtmEntity> persons = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .toList();
        assertEquals(10, persons.size());
    }

    private static void copyResource(String resourceName, Path target) throws IOException {
        try (InputStream in = Objects.requireNonNull(
                BePepCourtOfAuditParserTest.class.getClassLoader().getResourceAsStream(resourceName),
                "Ressource de test introuvable : " + resourceName)) {
            Files.copy(in, target);
        }
    }
}
