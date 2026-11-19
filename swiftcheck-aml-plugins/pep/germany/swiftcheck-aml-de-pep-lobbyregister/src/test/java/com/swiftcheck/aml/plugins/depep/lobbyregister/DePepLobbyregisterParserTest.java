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

    @Test
    void testParseSampleData(@TempDir Path tempDir) throws Exception {
        Path sampleJson = Path.of("src/test/resources/test-lobbyregister-sample.json");
        assertTrue(Files.exists(sampleJson), "Le fichier sample doit exister");

        Path outputFile = tempDir.resolve("output.ndjson");
        ParserContext ctx = new ParserContext("de_pep_lobbyregister", sampleJson, tempDir, outputFile, Map.of());

        DePepLobbyregisterParser parser = new DePepLobbyregisterParser();
        ParserResult result = parser.parse(ctx);

        assertNotNull(result);
        assertTrue(Files.exists(outputFile), "Le fichier NDJSON doit avoir ete cree");

        List<String> lines = Files.readAllLines(outputFile);
        assertTrue(lines.size() >= 5, "Doit contenir au moins 5 entites FtM");

        // 1. Verifier la presence de Siemens AG
        boolean foundSiemens = lines.stream().anyMatch(l -> l.contains("Siemens AG") && l.contains("Company"));
        assertTrue(foundSiemens, "Siemens AG doit etre enregistre comme Company");

        // 2. Verifier Oktay Ay (Ancien mandat BMF -> PEP A.2)
        boolean foundOktayPep = lines.stream().anyMatch(l -> l.contains("Oktay Ay") && l.contains("role.pep"));
        assertTrue(foundOktayPep, "Oktay Ay doit porter le topic role.pep (ancien mandat BMF)");

        // 3. Verifier Roland Busch (Co-representant d'une PEP -> RCA.1 GwG § 1 Abs. 14)
        boolean foundRolandRca = lines.stream().anyMatch(l -> l.contains("Roland Busch") && l.contains("role.rca"));
        assertTrue(foundRolandRca, "Roland Busch doit etre tagge role.rca car co-representant de la PEP Oktay Ay");

        // 4. Verifier le lien relationnel Associate entre Oktay Ay et Roland Busch
        boolean foundAssociate = lines.stream().anyMatch(l -> l.contains("Associate") && l.contains("GwG § 1 Abs. 14"));
        assertTrue(foundAssociate, "L'entite Associate doit justifier le lien RCA par GwG § 1 Abs. 14");

        // 5. Verifier l'organisation cliente
        boolean foundClient = lines.stream().anyMatch(l -> l.contains("Energieallianz Deutschland GmbH"));
        assertTrue(foundClient, "Le client Energieallianz doit etre present");

        // 6. Verifier l'employe
        boolean foundEmployee = lines.stream().anyMatch(l -> l.contains("Daniel Müller"));
        assertTrue(foundEmployee, "L'employe de lobbying Daniel Müller doit etre present");
    }

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

        assertEquals(2, records.size(), "Doit retourner les 2 fiches du fichier sample");
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
}
