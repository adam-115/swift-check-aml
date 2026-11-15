package com.swiftcheck.aml.plugins.depep.defencecir;

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

class DePepDefenceCirParserTest {

    @Test
    void testParseOfflineFixtures(@TempDir Path tempDir) throws Exception {
        Path resourcesDir = Path.of("src/test/resources/bmvg_cir");
        assertTrue(Files.exists(resourcesDir.resolve("kommando-cir.html")), "kommando-cir.html doit exister");
        assertTrue(Files.exists(resourcesDir.resolve("sample-vita.pdf")), "sample-vita.pdf doit exister");

        Path outputFile = tempDir.resolve("output.ndjson");
        Path rawDir = tempDir.resolve("raw");
        Files.createDirectories(rawDir);

        ParserContext ctx = new ParserContext("de_pep_defence_cir", resourcesDir.resolve("kommando-cir.html"), rawDir, outputFile, Map.of());
        DePepDefenceCirParser parser = new DePepDefenceCirParser();

        ParserResult result = parser.parse(ctx);
        assertNotNull(result);
        assertTrue(Files.exists(outputFile));
        assertEquals(0, result.conflictCount(), "Aucun conflit d'ID");
        assertEquals(0, result.rejectedCount(), "Aucune valeur rejetée par le modèle FtM");

        List<FtmEntity> entities = result.entities();
        assertFalse(entities.isEmpty());

        // Positions: Inspekteur CIR, Zentrum Digitalisierung, Zentrum für Geoinformationswesen
        assertTrue(entities.stream().anyMatch(e -> "Position".equals(e.getSchema())
                && e.getValues("name").contains("Inspekteur Cyber- und Informationsraum")));
        assertTrue(entities.stream().anyMatch(e -> "Position".equals(e.getSchema())
                && e.getValues("name").contains("Kommandeur Zentrum Digitalisierung der Bundeswehr")));
        assertTrue(entities.stream().anyMatch(e -> "Position".equals(e.getSchema())
                && e.getValues("name").contains("Kommandeur Zentrum für Geoinformationswesen der Bundeswehr")));

        // 1. Thomas Daum (Vizeadmiral - Inspekteur CIR)
        FtmEntity daum = entities.stream()
                .filter(e -> "Person".equals(e.getSchema()) && e.getValues("name").contains("Thomas Daum"))
                .findFirst().orElseThrow();
        assertEquals("de", daum.getValues("country").iterator().next());
        assertEquals("de", daum.getValues("nationality").iterator().next());
        assertEquals("Vizeadmiral", daum.getValues("title").iterator().next());
        assertTrue(daum.getValues("topics").contains("role.pep"));

        FtmEntity daumOcc = entities.stream()
                .filter(e -> "Occupancy".equals(e.getSchema()) && e.getValues("holder").contains(daum.getId()))
                .findFirst().orElseThrow();
        assertEquals("current", daumOcc.getValues("status").iterator().next());
        assertEquals("2020-09-01", daumOcc.getValues("startDate").iterator().next());

        // 2. Michael Stingl (Brigadegeneral - Zentrum Digitalisierung)
        FtmEntity stingl = entities.stream()
                .filter(e -> "Person".equals(e.getSchema()) && e.getValues("name").contains("Michael Stingl"))
                .findFirst().orElseThrow();
        assertEquals("Brigadegeneral", stingl.getValues("title").iterator().next());

        FtmEntity stinglOcc = entities.stream()
                .filter(e -> "Occupancy".equals(e.getSchema()) && e.getValues("holder").contains(stingl.getId()))
                .findFirst().orElseThrow();
        assertEquals("2022-10-01", stinglOcc.getValues("startDate").iterator().next());

        // 3. Christian Reppin (Flottillenadmiral - Geoinformation)
        FtmEntity reppin = entities.stream()
                .filter(e -> "Person".equals(e.getSchema()) && e.getValues("name").contains("Christian Reppin"))
                .findFirst().orElseThrow();
        assertEquals("Flottillenadmiral", reppin.getValues("title").iterator().next());

        FtmEntity reppinOcc = entities.stream()
                .filter(e -> "Occupancy".equals(e.getSchema()) && e.getValues("holder").contains(reppin.getId()))
                .findFirst().orElseThrow();
        assertEquals("2026-01-01", reppinOcc.getValues("startDate").iterator().next());

        // Source records
        List<SourceRecord> records = parser.readSourceRecords(ctx);
        assertEquals(3, records.size(), "3 commandants/officiers généraux CIR doivent être extraits");
    }

    @Test
    void testParseGermanMonthYear() {
        assertEquals("2020-09-01", DePepDefenceCirParser.parseGermanMonthYear("September 2020"));
        assertEquals("2022-10-01", DePepDefenceCirParser.parseGermanMonthYear("Oktober 2022"));
        assertEquals("2026-01-01", DePepDefenceCirParser.parseGermanMonthYear("Januar 2026"));
        assertEquals("2023-05-01", DePepDefenceCirParser.parseGermanMonthYear("05/2023"));
        assertEquals("2021-01-01", DePepDefenceCirParser.parseGermanMonthYear("2021"));
        assertNull(DePepDefenceCirParser.parseGermanMonthYear(null));
    }

    @Test
    void testSha256Computation() {
        byte[] data = "Bundeswehr CIR Test".getBytes(StandardCharsets.UTF_8);
        String hash = DePepDefenceCirParser.computeSha256(data);
        assertNotNull(hash);
        assertEquals(64, hash.length(), "Le hash SHA-256 doit comporter 64 caractères hexadécimaux");
    }

    @Test
    void testRequireParameterWhenMissingInLiveMode(@TempDir Path tempDir) {
        Path outputFile = tempDir.resolve("output.ndjson");
        Path rawDir = tempDir.resolve("raw");
        ParserContext ctx = new ParserContext("de_pep_defence_cir", null, rawDir, outputFile, Map.of());
        DePepDefenceCirParser parser = new DePepDefenceCirParser();

        assertThrows(IllegalArgumentException.class, () -> parser.parse(ctx),
                "Doit lever IllegalArgumentException si kommandoCirUrl manque dans ParserContext");
    }
}

