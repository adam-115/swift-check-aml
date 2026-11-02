package com.swiftcheck.aml.plugins.be.pep.nationalbank;

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
 * Tests unitaires hors-ligne de {@link BePepNationalBankParser} sur les pages
 * officielles du Comité de direction et du Conseil de régence de la BNB.
 */
class BePepNationalBankParserTest {

    @Test
    void parsesGovernorDirectorsAndRegents(@TempDir Path tempDir) throws IOException {
        copyResource("nbb-comite-direction.html", tempDir.resolve(BePepNationalBankParser.FILE_DIRECTORS));
        copyResource("nbb-conseil-regence.html", tempDir.resolve(BePepNationalBankParser.FILE_REGENTS));

        BePepNationalBankParser parser = new BePepNationalBankParser();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(
                BePepNationalBankParser.ID_PREFIX, BePepNationalBankParser.TOPIC);

        List<BePepNationalBankParser.NbbMemberRecord> records = parser.convert(tempDir, dataset);
        assertEquals(20, records.size(), "5 membres du Comité de direction + 14 régents + 1 représentant au Conseil de régence = 20");

        assertTrue(records.stream().anyMatch(r ->
                "Pierre Wunsch".equals(r.fullName()) && "Gouverneur".equals(r.role())));
        assertTrue(records.stream().anyMatch(r ->
                "Tom Dechaene".equals(r.fullName()) && "Vice-gouverneur".equals(r.role())));
        assertTrue(records.stream().anyMatch(r ->
                "Annelore Van Hecke".equals(r.fullName()) && "Présidente du Conseil de régence".equals(r.role())));
        assertTrue(records.stream().anyMatch(r ->
                "Pieter Timmermans".equals(r.fullName()) && "Régent".equals(r.role())));

        List<FtmEntity> persons = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .toList();
        assertEquals(20, persons.size());
    }

    private static void copyResource(String resourceName, Path target) throws IOException {
        try (InputStream in = Objects.requireNonNull(
                BePepNationalBankParserTest.class.getClassLoader().getResourceAsStream(resourceName),
                "Ressource de test introuvable : " + resourceName)) {
            Files.copy(in, target);
        }
    }
}
