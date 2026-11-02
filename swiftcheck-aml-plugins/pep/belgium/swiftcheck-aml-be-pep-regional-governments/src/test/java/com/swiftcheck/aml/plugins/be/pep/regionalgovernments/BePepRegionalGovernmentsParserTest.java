package com.swiftcheck.aml.plugins.be.pep.regionalgovernments;

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
 * Tests unitaires hors-ligne de {@link BePepRegionalGovernmentsParser}
 * sur les pages réelles du Gouvernement flamand, du Gouvernement de la
 * Fédération Wallonie-Bruxelles et du Gouvernement de la Communauté germanophone.
 */
class BePepRegionalGovernmentsParserTest {

    @Test
    void parsesFlemishFwbAndGermanSpeakingGovernments(@TempDir Path tempDir) throws IOException {
        copyResource("vlaanderen.html", tempDir.resolve("vlaanderen.html"));
        copyResource("cfwb.html", tempDir.resolve("cfwb.html"));
        copyResource("ostbelgien.html", tempDir.resolve("ostbelgien.html"));

        BePepRegionalGovernmentsParser parser = new BePepRegionalGovernmentsParser();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(
                BePepRegionalGovernmentsParser.ID_PREFIX, BePepRegionalGovernmentsParser.TOPIC);

        List<BePepRegionalGovernmentsParser.MinisterRecord> records = parser.convert(tempDir, dataset);
        assertEquals(19, records.size(), "9 (Flandre) + 6 (FWB) + 4 (Ostbelgien) = 19 ministres attendus");

        assertTrue(records.stream().anyMatch(r ->
                "Matthias Diependaele".equals(r.fullName())
                && r.roleCategory().startsWith("Ministre-président")));
        assertTrue(records.stream().anyMatch(r ->
                "Ben Weyts".equals(r.fullName())
                && r.roleCategory().startsWith("Vice-ministre-président")));
        assertTrue(records.stream().anyMatch(r ->
                "Elisabeth DEGRYSE".equals(r.fullName())
                && r.roleCategory().startsWith("Ministre-président")));
        assertTrue(records.stream().anyMatch(r ->
                "Oliver Paasch".equals(r.fullName())
                && r.roleCategory().startsWith("Ministre-président")));
        assertTrue(records.stream().anyMatch(r ->
                "Lydia Klinkenberg".equals(r.fullName())
                && r.roleCategory().startsWith("Ministre – Communauté germanophone")));

        List<FtmEntity> persons = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .toList();
        assertEquals(19, persons.size());
    }

    private static void copyResource(String resourceName, Path target) throws IOException {
        try (InputStream in = Objects.requireNonNull(
                BePepRegionalGovernmentsParserTest.class.getClassLoader().getResourceAsStream(resourceName),
                "Ressource de test introuvable : " + resourceName)) {
            Files.copy(in, target);
        }
    }
}
