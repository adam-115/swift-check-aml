package com.swiftcheck.aml.plugins.depep.defencemarine;

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

class DePepDefenceMarineParserTest {

    @Test
    void testParseOfflineFixtures(@TempDir Path tempDir) throws Exception {
        Path resourcesDir = Path.of("src/test/resources/bmvg_marine");
        assertTrue(Files.exists(resourcesDir.resolve("einsatzflottille-1.html")), "einsatzflottille-1.html doit exister");
        assertTrue(Files.exists(resourcesDir.resolve("sample-vita.pdf")), "sample-vita.pdf doit exister");

        Path outputFile = tempDir.resolve("output.ndjson");
        Path rawDir = tempDir.resolve("raw");
        Files.createDirectories(rawDir);

        ParserContext ctx = new ParserContext("de_pep_defence_marine", resourcesDir.resolve("einsatzflottille-1.html"), rawDir, outputFile, Map.of());
        DePepDefenceMarineParser parser = new DePepDefenceMarineParser();

        ParserResult result = parser.parse(ctx);
        assertNotNull(result);
        assertTrue(Files.exists(outputFile));
        assertEquals(0, result.conflictCount(), "Aucun conflit d'ID");
        assertEquals(0, result.rejectedCount(), "Aucune valeur rejetée par le modèle FtM");

        List<FtmEntity> entities = result.entities();
        assertFalse(entities.isEmpty());

        // Positions: Einsatzflottille 1, Einsatzflottille 2, Marineschule Mürwik
        assertTrue(entities.stream().anyMatch(e -> "Position".equals(e.getSchema())
                && e.getValues("name").contains("Kommandeur Einsatzflottille 1")));
        assertTrue(entities.stream().anyMatch(e -> "Position".equals(e.getSchema())
                && e.getValues("name").contains("Kommandeur Einsatzflottille 2")));
        assertTrue(entities.stream().anyMatch(e -> "Position".equals(e.getSchema())
                && e.getValues("name").contains("Kommandeur Marineschule Mürwik")));

        // 1. Sascha Helge Rackwitz (Flottillenadmiral - Einsatzflottille 1)
        FtmEntity rackwitz = entities.stream()
                .filter(e -> "Person".equals(e.getSchema()) && e.getValues("name").contains("Sascha Helge Rackwitz"))
                .findFirst().orElseThrow();
        assertEquals("de", rackwitz.getValues("country").iterator().next());
        assertEquals("de", rackwitz.getValues("nationality").iterator().next());
        assertEquals("Flottillenadmiral", rackwitz.getValues("title").iterator().next());
        assertTrue(rackwitz.getValues("topics").contains("role.pep"));

        FtmEntity rackwitzOcc = entities.stream()
                .filter(e -> "Occupancy".equals(e.getSchema()) && e.getValues("holder").contains(rackwitz.getId()))
                .findFirst().orElseThrow();
        assertEquals("current", rackwitzOcc.getValues("status").iterator().next());
        assertEquals("2023-01-01", rackwitzOcc.getValues("startDate").iterator().next());

        // 2. Axel Schulz (Flottillenadmiral - Einsatzflottille 2)
        FtmEntity schulz = entities.stream()
                .filter(e -> "Person".equals(e.getSchema()) && e.getValues("name").contains("Axel Schulz"))
                .findFirst().orElseThrow();
        assertEquals("Flottillenadmiral", schulz.getValues("title").iterator().next());

        FtmEntity schulzOcc = entities.stream()
                .filter(e -> "Occupancy".equals(e.getSchema()) && e.getValues("holder").contains(schulz.getId()))
                .findFirst().orElseThrow();
        assertEquals("2021-09-01", schulzOcc.getValues("startDate").iterator().next());

        // 3. Jens Nemeyer (Flottillenadmiral - Marineschule Mürwik)
        FtmEntity nemeyer = entities.stream()
                .filter(e -> "Person".equals(e.getSchema()) && e.getValues("name").contains("Jens Nemeyer"))
                .findFirst().orElseThrow();
        assertEquals("Flottillenadmiral", nemeyer.getValues("title").iterator().next());

        FtmEntity nemeyerOcc = entities.stream()
                .filter(e -> "Occupancy".equals(e.getSchema()) && e.getValues("holder").contains(nemeyer.getId()))
                .findFirst().orElseThrow();
        assertEquals("2023-09-01", nemeyerOcc.getValues("startDate").iterator().next());

        // Source records
        List<SourceRecord> records = parser.readSourceRecords(ctx);
        assertEquals(3, records.size(), "3 amiraux doivent être extraits");
    }

    @Test
    void testParseGermanMonthYear() {
        assertEquals("2023-01-01", DePepDefenceMarineParser.parseGermanMonthYear("Januar 2023"));
        assertEquals("2021-09-01", DePepDefenceMarineParser.parseGermanMonthYear("September 2021"));
        assertEquals("2023-09-01", DePepDefenceMarineParser.parseGermanMonthYear("09/2023"));
        assertEquals("2022-01-01", DePepDefenceMarineParser.parseGermanMonthYear("2022"));
        assertNull(DePepDefenceMarineParser.parseGermanMonthYear(null));
    }

    @Test
    void testSha256Computation() {
        byte[] data = "Bundeswehr Marine Test".getBytes(StandardCharsets.UTF_8);
        String hash = DePepDefenceMarineParser.computeSha256(data);
        assertNotNull(hash);
        assertEquals(64, hash.length(), "Le hash SHA-256 doit comporter 64 caractères hexadécimaux");
    }

    @Test
    void testRequireParameterWhenMissingInLiveMode(@TempDir Path tempDir) {
        Path outputFile = tempDir.resolve("output.ndjson");
        Path rawDir = tempDir.resolve("raw");
        ParserContext ctx = new ParserContext("de_pep_defence_marine", null, rawDir, outputFile, Map.of());
        DePepDefenceMarineParser parser = new DePepDefenceMarineParser();

        assertThrows(IllegalArgumentException.class, () -> parser.parse(ctx),
                "Doit lever IllegalArgumentException si marineKommandoUrl manque dans ParserContext");
    }
}

