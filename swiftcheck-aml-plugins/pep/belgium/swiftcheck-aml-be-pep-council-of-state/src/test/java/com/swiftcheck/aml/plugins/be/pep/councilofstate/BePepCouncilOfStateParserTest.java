package com.swiftcheck.aml.plugins.be.pep.councilofstate;

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
 * Tests unitaires hors-ligne de {@link BePepCouncilOfStateParser} sur l'annuaire
 * et les compositions officielles du Conseil d'État de Belgique.
 */
class BePepCouncilOfStateParserTest {

    @Test
    void parsesCouncilMembersAndAuditors(@TempDir Path tempDir) throws IOException {
        copyResource("council-of-state-direction.html", tempDir.resolve(BePepCouncilOfStateParser.FILE_NAME));
        copyResource("chambres.pdf", tempDir.resolve(BePepCouncilOfStateParser.CHAMBRES_FILE_NAME));
        copyResource("auditfr.pdf", tempDir.resolve(BePepCouncilOfStateParser.AUDIT_FILE_NAME));

        BePepCouncilOfStateParser parser = new BePepCouncilOfStateParser();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(
                BePepCouncilOfStateParser.ID_PREFIX, BePepCouncilOfStateParser.TOPIC);

        List<BePepCouncilOfStateParser.MemberRecord> records = parser.convert(tempDir, dataset);
        assertTrue(records.size() >= 50, "Le Conseil d'État complet doit contenir au moins 50 magistrats (obtenu: " + records.size() + ")");

        // Vérification des présidents et conseillers
        assertTrue(records.stream().anyMatch(r -> r.displayName().contains("Van Vaerenbergh") || r.displayName().contains("VAN VAERENBERGH")));
        assertTrue(records.stream().anyMatch(r -> r.displayName().contains("Vandernacht") || r.displayName().contains("VANDERNACHT")));

        // Vérification des auditeurs
        assertTrue(records.stream().anyMatch(r -> r.displayName().contains("DELGRANGE")));
        assertTrue(records.stream().anyMatch(r -> r.displayName().contains("TELLIER")));

        // Exclusion des administratifs
        assertFalse(records.stream().anyMatch(r -> r.function().toLowerCase().contains("greffier")));
        assertFalse(records.stream().anyMatch(r -> r.function().toLowerCase().contains("administrateur")));

        List<FtmEntity> persons = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .toList();
        assertTrue(persons.size() >= 50);
    }

    private static void copyResource(String resourceName, Path target) throws IOException {
        try (InputStream in = Objects.requireNonNull(
                BePepCouncilOfStateParserTest.class.getClassLoader().getResourceAsStream(resourceName),
                "Ressource de test introuvable : " + resourceName)) {
            Files.copy(in, target);
        }
    }
}