package com.swiftcheck.aml.plugins.depep.lobbyregister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;

class DePepLobbyregisterParserTest {

    /**
     * Fichier complet du registre (plusieurs centaines de Mo) : exécuté seulement si LOBBYREGISTER_SAMPLE désigne
     * une réponse brute de l'API (par exemple le fichier archivé par le back). Aucun conflit d'ID n'est accepté :
     * la publication serait bloquée.
     */
    @Test
    void fullRegisterHasNoIdConflict(@TempDir Path tempDir) throws Exception {
        String sample = System.getenv("LOBBYREGISTER_SAMPLE");
        org.junit.jupiter.api.Assumptions.assumeTrue(sample != null && Files.exists(Path.of(sample)));
        DePepLobbyregisterParser parser = new DePepLobbyregisterParser();
        LobbyregisterModels.DetailedSearchResults data = new LobbyregisterApiClient().parseRawFile(Path.of(sample));
        com.swiftcheck.aml.sdk.FtmDatasetBuilder dataset =
                new com.swiftcheck.aml.sdk.FtmDatasetBuilder(DePepLobbyregisterParser.ID_PREFIX);
        parser.convert(data, dataset, null);
        var report = dataset.qualityReport();
        System.out.println(report + " " + report.getRejectedSamples());
        assertEquals(0, report.getIdConflictCount(), String.valueOf(report.getRejectedSamples()));
    }

    @Test
    void testReadSourceRecords(@TempDir Path tempDir) throws Exception {
        Path sampleJson = Path.of("src/test/resources/test-lobbyregister-sample.json");
        ParserContext ctx = new ParserContext("de_pep_lobbyregister", sampleJson, tempDir, tempDir.resolve("out.ndjson"), Map.of());

        DePepLobbyregisterParser parser = new DePepLobbyregisterParser();
        List<SourceRecord> records = parser.readSourceRecords(ctx);

        assertEquals(3, records.size(), "Doit retourner les 3 fiches du fichier sample");
        assertEquals("de-lobby-R001875", records.get(0).entityId());
        assertEquals("Siemens AG", records.get(0).name());
        assertEquals("de-lobby-R009999", records.get(1).entityId());
        assertEquals("Ehemalige Lobbyagentur Berlin", records.get(1).name());
    }

    @Test
    void testHttpFailureThrowsIOException(@TempDir Path tempDir) {
        LobbyregisterApiClient brokenClient = new LobbyregisterApiClient() {
            @Override
            public IngestionResult fetchAndSave(String apiUrl, String apiKey, Path rawDir) throws IOException {
                throw new IOException("L'API Lobbyregister a renvoye une erreur HTTP 500");
            }
        };

        DePepLobbyregisterParser parser = new DePepLobbyregisterParser(brokenClient);
        ParserContext ctx = new ParserContext("de_pep_lobbyregister", null, tempDir, tempDir.resolve("out.ndjson"), Map.of("url", "https://invalid-url"));

        assertThrows(IOException.class, () -> parser.parse(ctx), "Une erreur HTTP 500 doit lever une IOException");
    }
    /**
     * Controle sur le registre complet, lance a la main :
     * {@code mvn test -Dlobby.full=<chemin du lobbyregister.json telecharge depuis /sucheDetailJson>}.
     */
    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "lobby.full", matches = ".+")
    void fullRegisterHasNoRejectedValueAndNoConflict(@TempDir Path tempDir) throws Exception {
        Path file = Path.of(System.getProperty("lobby.full"));
        // -Dlobby.index=<index écrit par le core> : croisement avec la base PEP (Story 5, étape 1).
        String index = System.getProperty("lobby.index");
        ParserContext ctx = new ParserContext("de_pep_lobbyregister", file, tempDir, tempDir.resolve("out.ndjson"),
                index == null ? Map.of() : Map.of("pepIndexFile", index));
        ParserResult result = new DePepLobbyregisterParser().parse(ctx);
        String dump = System.getProperty("lobby.dump");
        if (dump != null) Files.copy(tempDir.resolve("out.ndjson"), Path.of(dump), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        System.out.println(result.message());
        System.out.println("rejets=" + result.rejectedCount() + " conflits=" + result.conflictCount() + " " + result.rejectedByProperty());
        assertEquals(0, result.rejectedCount(), "valeurs rejetees : " + result.rejectedByProperty());
        assertEquals(0, result.conflictCount(), "conflits d ID");
    }

    private static List<String> parseSample(Path tempDir, Map<String, String> params) throws Exception {
        Path sampleJson = Path.of("src/test/resources/test-lobbyregister-sample.json");
        Path outputFile = tempDir.resolve("output.ndjson");
        ParserContext ctx = new ParserContext("de_pep_lobbyregister", sampleJson, tempDir, outputFile, params);
        ParserResult result = new DePepLobbyregisterParser().parse(ctx);
        assertNotNull(result);
        System.out.println(result.message());
        return Files.readAllLines(outputFile);
    }

    private static String person(List<String> lines, String name) {
        return lines.stream().filter(l -> l.contains("\"schema\":\"Person\"") && l.contains("\"name\":[\"" + name + "\"]"))
                .findFirst().orElse(null);
    }

    @Test
    void onlyPepRankFunctionsMakeAPep(@TempDir Path tempDir) throws Exception {
        List<String> lines = parseSample(tempDir, Map.of());
        // Un Referent du BMF n'est pas une PEP (fonction de l'administration sans rang PEP).
        String ay = person(lines, "Oktay Ay");
        assertTrue(ay == null || !ay.contains("role.pep"), "Oktay Ay (Referent) ne doit pas etre PEP : " + ay);
        String schulz = person(lines, "Martin Schulz");
        assertNotNull(schulz);
        assertTrue(schulz.contains("role.pep") && schulz.contains("pep_status: ACTIVE_PEP")
                && schulz.contains("Nr. 1 b)") && schulz.contains("Mitglied des Deutschen Bundestages"));
        String pronold = person(lines, "Florian Pronold");
        assertNotNull(pronold);
        assertTrue(pronold.contains("role.pep") && pronold.contains("pep_status: EXPIRED"), pronold);
    }

    @Test
    void rcaRulesWithAuditMotif(@TempDir Path tempDir) throws Exception {
        List<String> lines = parseSample(tempDir, Map.of());
        // Règle 1 : co-représentant légal de la PEP.
        String busch = person(lines, "Roland Busch");
        assertNotNull(busch);
        assertTrue(busch.contains("role.rca") && busch.contains("rca_status: RCA.1") && busch.contains("Regel 1")
                && busch.contains("GwG § 1 Abs. 14 Nr. 2") && busch.contains("Martin Schulz") && busch.contains("[R001875]")
                && busch.contains("Vorsitzender des Vorstands") && busch.contains("https://www.lobbyregister.bundestag.de/suche/R001875/87507"), busch);
        assertTrue(!busch.contains("role.pep"));
        // Règle 2 : personne chargée du lobbying d'un cabinet de 4 personnes dirigé par la PEP.
        String mueller = person(lines, "Daniel Müller");
        assertNotNull(mueller);
        assertTrue(mueller.contains("role.rca") && mueller.contains("Regel 2"), mueller);
        // Règle 3 + RCA.2 : mandat pour Siemens (dirigé par une PEP) d'une agence désinscrite.
        String klein = person(lines, "Eva Klein");
        assertNotNull(klein);
        assertTrue(klein.contains("rca_status: RCA.2") && klein.contains("Regel 3") && klein.contains("beendet am 2026-11-06"), klein);
        assertTrue(lines.stream().anyMatch(l -> l.contains("\"schema\":\"Associate\"") && l.contains("Eva Klein") == false
                && l.contains("\"endDate\":[\"2026-11-06\"]")));
        // Une PEP EXPIRED ne fonde aucun lien : la collaboratrice de Pronold n'est pas RCA.
        assertEquals(null, person(lines, "Lena Vogt"));
        // Personne sans lien avec une PEP : non publiée par défaut.
        assertEquals(null, person(lines, "Hans Schmidt"));
    }

    @Test
    void publishAllPersonsKeepsUnrelatedPeople(@TempDir Path tempDir) throws Exception {
        List<String> lines = parseSample(tempDir, Map.of("publishAllPersons", "true"));
        String schmidt = person(lines, "Hans Schmidt");
        assertNotNull(schmidt);
        assertTrue(!schmidt.contains("role.pep") && !schmidt.contains("role.rca"));
    }
}


