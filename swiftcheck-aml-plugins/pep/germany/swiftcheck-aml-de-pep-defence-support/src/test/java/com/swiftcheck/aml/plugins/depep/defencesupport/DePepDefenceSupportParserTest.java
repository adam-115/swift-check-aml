package com.swiftcheck.aml.plugins.depep.defencesupport;

import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DePepDefenceSupportParserTest {

    @Test
    void testParseOfflineFixtures(@TempDir Path tempDir) throws Exception {
        Path resourcesDir = Path.of("src/test/resources/bmvg_support");
        assertTrue(Files.exists(resourcesDir.resolve("unterstuetzungskommando.html")), "unterstuetzungskommando.html doit exister");
        assertTrue(Files.exists(resourcesDir.resolve("sample-vita.pdf")), "sample-vita.pdf doit exister");

        Path outputFile = tempDir.resolve("output.ndjson");
        Path rawDir = tempDir.resolve("raw");
        Files.createDirectories(rawDir);

        ParserContext ctx = new ParserContext("de_pep_defence_support", resourcesDir.resolve("unterstuetzungskommando.html"), rawDir, outputFile, Map.of());
        DePepDefenceSupportParser parser = new DePepDefenceSupportParser();

        ParserResult result = parser.parse(ctx);
        assertNotNull(result);
        assertTrue(Files.exists(outputFile));
        assertEquals(0, result.conflictCount(), "Aucun conflit d'ID");
        assertEquals(0, result.rejectedCount(), "Aucune valeur rejetée par le modèle FtM");

        List<FtmEntity> entities = result.entities();
        assertFalse(entities.isEmpty());

        // Positions: Unterstützungskommando, BAAINBw, BAMAD
        assertTrue(entities.stream().anyMatch(e -> "Position".equals(e.getSchema())
                && e.getValues("name").contains("Kommandeur Unterstützungskommando der Bundeswehr")));
        assertTrue(entities.stream().anyMatch(e -> "Position".equals(e.getSchema())
                && e.getValues("name").contains("Präsidentin BAAINBw")));
        assertTrue(entities.stream().anyMatch(e -> "Position".equals(e.getSchema())
                && e.getValues("name").contains("Präsidentin Bundesamt für den Militärischen Abschirmdienst")));

        // 1. Gerald Funke (Generalmajor - Unterstützungskommando)
        FtmEntity funke = entities.stream()
                .filter(e -> "Person".equals(e.getSchema()) && e.getValues("name").contains("Gerald Funke"))
                .findFirst().orElseThrow();
        assertEquals("de", funke.getValues("country").iterator().next());
        assertEquals("de", funke.getValues("nationality").iterator().next());
        assertEquals("Generalmajor", funke.getValues("title").iterator().next());
        assertTrue(funke.getValues("topics").contains("role.pep"));

        FtmEntity funkeOcc = entities.stream()
                .filter(e -> "Occupancy".equals(e.getSchema()) && e.getValues("holder").contains(funke.getId()))
                .findFirst().orElseThrow();
        assertEquals("current", funkeOcc.getValues("status").iterator().next());
        assertEquals("2024-10-01", funkeOcc.getValues("startDate").iterator().next());

        // 2. Annette Lehnigk-Emden (Präsidentin BAAINBw)
        FtmEntity lehnigk = entities.stream()
                .filter(e -> "Person".equals(e.getSchema()) && e.getValues("name").contains("Annette Lehnigk-Emden"))
                .findFirst().orElseThrow();
        assertEquals("Präsidentin", lehnigk.getValues("title").iterator().next());

        FtmEntity lehnigkOcc = entities.stream()
                .filter(e -> "Occupancy".equals(e.getSchema()) && e.getValues("holder").contains(lehnigk.getId()))
                .findFirst().orElseThrow();
        assertEquals("2023-04-01", lehnigkOcc.getValues("startDate").iterator().next());

        // 3. Martina Rosenberg (Präsidentin BAMAD)
        FtmEntity rosenberg = entities.stream()
                .filter(e -> "Person".equals(e.getSchema()) && e.getValues("name").contains("Martina Rosenberg"))
                .findFirst().orElseThrow();
        assertEquals("Präsidentin", rosenberg.getValues("title").iterator().next());

        FtmEntity rosenbergOcc = entities.stream()
                .filter(e -> "Occupancy".equals(e.getSchema()) && e.getValues("holder").contains(rosenberg.getId()))
                .findFirst().orElseThrow();
        assertEquals("2020-11-01", rosenbergOcc.getValues("startDate").iterator().next());

        // Source records
        List<SourceRecord> records = parser.readSourceRecords(ctx);
        assertEquals(3, records.size(), "3 dirigeants du soutien/armement doivent être extraits");
    }

    @Test
    void testParseGermanMonthYear() {
        assertEquals("2024-10-01", DePepDefenceSupportParser.parseGermanMonthYear("Oktober 2024"));
        assertEquals("2023-04-01", DePepDefenceSupportParser.parseGermanMonthYear("April 2023"));
        assertEquals("2020-11-01", DePepDefenceSupportParser.parseGermanMonthYear("November 2020"));
        assertEquals("2022-07-01", DePepDefenceSupportParser.parseGermanMonthYear("07/2022"));
        assertEquals("2025-01-01", DePepDefenceSupportParser.parseGermanMonthYear("2025"));
        assertNull(DePepDefenceSupportParser.parseGermanMonthYear(null));
    }

    @Test
    void testSha256Computation() {
        byte[] data = "Bundeswehr Support Test".getBytes(StandardCharsets.UTF_8);
        String hash = DePepDefenceSupportParser.computeSha256(data);
        assertNotNull(hash);
        assertEquals(64, hash.length(), "Le hash SHA-256 doit comporter 64 caractères hexadécimaux");
    }

    @Test
    void testRequireParameterWhenMissingInLiveMode(@TempDir Path tempDir) {
        Path outputFile = tempDir.resolve("output.ndjson");
        Path rawDir = tempDir.resolve("raw");
        ParserContext ctx = new ParserContext("de_pep_defence_support", null, rawDir, outputFile, Map.of());
        DePepDefenceSupportParser parser = new DePepDefenceSupportParser();

        assertThrows(IllegalArgumentException.class, () -> parser.parse(ctx),
                "Doit lever IllegalArgumentException si supportUrl manque dans ParserContext");
    }
}

