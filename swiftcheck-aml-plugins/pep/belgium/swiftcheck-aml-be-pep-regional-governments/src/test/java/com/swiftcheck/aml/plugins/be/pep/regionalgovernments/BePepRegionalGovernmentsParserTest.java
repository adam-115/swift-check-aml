package com.swiftcheck.aml.plugins.be.pep.regionalgovernments;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
        copyResource("wallonie.html", tempDir.resolve("wallonie.html"));
        copyResource("bruxelles.html", tempDir.resolve("bruxelles.html"));

        BePepRegionalGovernmentsParser parser = new BePepRegionalGovernmentsParser();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(
                BePepRegionalGovernmentsParser.ID_PREFIX, BePepRegionalGovernmentsParser.TOPIC);

        Map<String, String> params = Map.of("governments", readResourceString("regional-governments.json"));
        List<BePepRegionalGovernmentsParser.MinisterRecord> records = parser.convert(tempDir, dataset, params);
        assertEquals(35, records.size(), "35 ministres régionaux attendus (Flandre: 9, FWB: 6, Ostbelgien: 4, Wallonie: 8, Bruxelles: 8)");

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
        assertTrue(records.stream().anyMatch(r ->
                "Adrien Dolimont".equals(r.fullName())
                && r.roleCategory().startsWith("Ministre-président")));
        assertTrue(records.stream().anyMatch(r ->
                "Rudi Vervoort".equals(r.fullName())
                && r.roleCategory().startsWith("Ministre-président")));

        List<FtmEntity> persons = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .toList();
        assertEquals(35, persons.size());
    }

    @Test
    void supportsDynamicParametersOverride() throws IOException {
        Map<String, String> params = Map.of(
                "governments", readResourceString("regional-governments.json"),
                "delayMs", "500",
                "wallonie.url", "https://fr.wikipedia.org/w/api.php?action=parse&page=Gouvernement_Test&prop=wikitext&format=json"
        );
        BePepRegionalGovernmentsParser.GovernmentsConfig cfg = BePepRegionalGovernmentsParser.loadConfig(params);
        assertEquals(500, cfg.delayMs());
        assertEquals(5, cfg.governments().size());
        assertEquals("https://fr.wikipedia.org/w/api.php?action=parse&page=Gouvernement_Test&prop=wikitext&format=json",
                cfg.governments().stream()
                        .filter(g -> "wallonie".equals(g.code()))
                        .findFirst()
                        .orElseThrow()
                        .url());
        assertThrows(IllegalStateException.class, () -> BePepRegionalGovernmentsParser.loadConfig(Map.of()));
    }

    private static String readResourceString(String resourceName) throws IOException {
        try (InputStream in = Objects.requireNonNull(
                BePepRegionalGovernmentsParserTest.class.getClassLoader().getResourceAsStream(resourceName),
                "Ressource de test introuvable : " + resourceName)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void copyResource(String resourceName, Path target) throws IOException {
        try (InputStream in = Objects.requireNonNull(
                BePepRegionalGovernmentsParserTest.class.getClassLoader().getResourceAsStream(resourceName),
                "Ressource de test introuvable : " + resourceName)) {
            Files.copy(in, target);
        }
    }
}
