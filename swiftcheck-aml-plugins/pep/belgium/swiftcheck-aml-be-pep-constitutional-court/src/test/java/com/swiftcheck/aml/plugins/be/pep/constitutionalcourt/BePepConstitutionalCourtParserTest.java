package com.swiftcheck.aml.plugins.be.pep.constitutionalcourt;

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
 * Tests unitaires hors-ligne de {@link BePepConstitutionalCourtParser}
 * sur la page officielle {@code const-court.be}.
 */
class BePepConstitutionalCourtParserTest {

    @Test
    void parsesAllTwelveJudgesAndTwoPresidentsOnly(@TempDir Path tempDir) throws IOException {
        Path htmlFile = tempDir.resolve(BePepConstitutionalCourtParser.FILE_NAME);
        copyResource("const-court-organization.html", htmlFile);

        BePepConstitutionalCourtParser parser = new BePepConstitutionalCourtParser();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(
                BePepConstitutionalCourtParser.ID_PREFIX, BePepConstitutionalCourtParser.TOPIC);

        List<BePepConstitutionalCourtParser.JudgeRecord> records = parser.convert(htmlFile, dataset);
        assertEquals(12, records.size(), "La Cour constitutionnelle compte exactement 12 juges");

        long presidents = records.stream().filter(BePepConstitutionalCourtParser.JudgeRecord::president).count();
        assertEquals(2L, presidents, "Les 2 présidents (FR et NL) doivent être identifiés");

        assertTrue(records.stream().anyMatch(r -> "Pierre Nihoul".equals(r.fullName()) && r.president()));
        assertTrue(records.stream().anyMatch(r -> "Joséphine Moerman".equals(r.fullName()) && r.president()));
        assertTrue(records.stream().anyMatch(r -> "Sabine de Bethune".equals(r.fullName()) && !r.president()));

        List<FtmEntity> persons = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .toList();
        assertEquals(12, persons.size());
    }

    private static void copyResource(String resourceName, Path target) throws IOException {
        try (InputStream in = Objects.requireNonNull(
                BePepConstitutionalCourtParserTest.class.getClassLoader().getResourceAsStream(resourceName),
                "Ressource de test introuvable : " + resourceName)) {
            Files.copy(in, target);
        }
    }
}
