package com.swiftcheck.aml.plugins.depep.defenceheer;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.plugins.depep.defenceheer.DePepDefenceHeerParser.CommanderProfile;
import com.swiftcheck.aml.sdk.FtmEntity;

public class DePepDefenceHeerParserTest {

    @TempDir
    Path tempDir;

    @Test
    void testParseHeerOffline() throws Exception {
        Path resourcesDir = Path.of("src/test/resources/bmvg_heer");
        assertTrue(Files.exists(resourcesDir.resolve("kommando-heer.html")), "kommando-heer.html doit exister");
        assertTrue(Files.exists(resourcesDir.resolve("10-panzerdivision.html")), "10-panzerdivision.html doit exister");
        assertTrue(Files.exists(resourcesDir.resolve("sample-vita.pdf")), "sample-vita.pdf doit exister");

        Path outputFile = tempDir.resolve("output.ndjson");
        Path rawDir = tempDir.resolve("raw");
        Files.createDirectories(rawDir);

        ParserContext ctx = new ParserContext("de_pep_defence_heer", resourcesDir.resolve("kommando-heer.html"), rawDir, outputFile, Map.of());
        DePepDefenceHeerParser parser = new DePepDefenceHeerParser();

        ParserResult result = parser.parse(ctx);
        assertNotNull(result);
        assertTrue(Files.exists(outputFile));
        assertEquals(0, result.conflictCount(), "Aucun conflit d'ID");
        assertEquals(0, result.rejectedCount(), "Aucune valeur rejetée par le modèle FtM");

        List<FtmEntity> entities = result.entities();
        assertFalse(entities.isEmpty());

        // Positions: Inspekteur des Heeres, Kommandeur Feldheer, Kommandeur 10. Panzerdivision
        assertTrue(entities.stream().anyMatch(e -> "Position".equals(e.getSchema())
                && e.getValues("name").contains("Inspekteur des Heeres")));
        assertTrue(entities.stream().anyMatch(e -> "Position".equals(e.getSchema())
                && e.getValues("name").contains("Kommandeur Feldheer")));
        assertTrue(entities.stream().anyMatch(e -> "Position".equals(e.getSchema())
                && e.getValues("name").contains("Kommandeur 10. Panzerdivision")));

        // 1. Dr. Christian Freuding (Generalleutnant - InspH)
        FtmEntity freuding = entities.stream()
                .filter(e -> "Person".equals(e.getSchema()) && e.getValues("name").contains("Christian Freuding"))
                .findFirst().orElseThrow();
        assertEquals("de", freuding.getValues("country").iterator().next());
        assertEquals("de", freuding.getValues("nationality").iterator().next());
        assertEquals("Generalleutnant", freuding.getValues("title").iterator().next());
        assertTrue(freuding.getValues("topics").contains("role.pep"));

        FtmEntity freudingOcc = entities.stream()
                .filter(e -> "Occupancy".equals(e.getSchema()) && e.getValues("holder").contains(freuding.getId()))
                .findFirst().orElseThrow();
        assertEquals("current", freudingOcc.getValues("status").iterator().next());
        assertEquals("2025-10-01", freudingOcc.getValues("startDate").iterator().next());

        // 2. Harald Gante (Generalleutnant - Kdr Feldheer)
        FtmEntity gante = entities.stream()
                .filter(e -> "Person".equals(e.getSchema()) && e.getValues("name").contains("Harald Gante"))
                .findFirst().orElseThrow();
        assertEquals("Generalleutnant", gante.getValues("title").iterator().next());

        FtmEntity ganteOcc = entities.stream()
                .filter(e -> "Occupancy".equals(e.getSchema()) && e.getValues("holder").contains(gante.getId()))
                .findFirst().orElseThrow();
        assertEquals("2023-09-01", ganteOcc.getValues("startDate").iterator().next());

        // 3. Jörg See (Generalmajor - 10. Panzerdivision)
        FtmEntity see = entities.stream()
                .filter(e -> "Person".equals(e.getSchema()) && e.getValues("name").contains("Jörg See"))
                .findFirst().orElseThrow();
        assertEquals("Generalmajor", see.getValues("title").iterator().next());

        FtmEntity seeOcc = entities.stream()
                .filter(e -> "Occupancy".equals(e.getSchema()) && e.getValues("holder").contains(see.getId()))
                .findFirst().orElseThrow();
        assertEquals("2024-09-01", seeOcc.getValues("startDate").iterator().next());

        // Source records
        List<SourceRecord> records = parser.readSourceRecords(ctx);
        assertEquals(6, records.size(), "6 officiers généraux doivent être extraits (5 Kommando Heer + 1 10. PzDiv)");
    }

    @Test
    void testParseGermanMonthYear() {
        assertEquals("2025-10-01", DePepDefenceHeerParser.parseGermanMonthYear("Oktober 2025"));
        assertEquals("2023-09-01", DePepDefenceHeerParser.parseGermanMonthYear("September 2023"));
        assertEquals("2024-09-01", DePepDefenceHeerParser.parseGermanMonthYear("09/2024"));
        assertEquals("2025-01-01", DePepDefenceHeerParser.parseGermanMonthYear("2025"));
        assertNull(DePepDefenceHeerParser.parseGermanMonthYear(null));
    }

    @Test
    void testSha256Computation() {
        byte[] data = "Bundeswehr Heer Test".getBytes(StandardCharsets.UTF_8);
        String hash = DePepDefenceHeerParser.computeSha256(data);
        assertNotNull(hash);
        assertEquals(64, hash.length(), "Le hash SHA-256 doit comporter 64 caractères hexadécimaux");
    }

    @Test
    void testRequireParameterWhenMissingInLiveMode(@TempDir Path tempDir) {
        Path outputFile = tempDir.resolve("output.ndjson");
        Path rawDir = tempDir.resolve("raw");
        ParserContext ctx = new ParserContext("de_pep_defence_heer", null, rawDir, outputFile, Map.of());
        DePepDefenceHeerParser parser = new DePepDefenceHeerParser();

        assertThrows(IllegalArgumentException.class, () -> parser.parse(ctx),
                "Doit lever IllegalArgumentException si kommandoHeerUrl manque dans ParserContext");
    }
}
