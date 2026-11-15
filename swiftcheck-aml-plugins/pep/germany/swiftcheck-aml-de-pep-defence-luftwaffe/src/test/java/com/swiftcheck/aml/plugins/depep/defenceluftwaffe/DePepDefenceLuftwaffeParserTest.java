package com.swiftcheck.aml.plugins.depep.defenceluftwaffe;

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

class DePepDefenceLuftwaffeParserTest {

    @Test
    void testParseOfflineFixtures(@TempDir Path tempDir) throws Exception {
        Path resourcesDir = Path.of("src/test/resources/bmvg_luftwaffe");
        assertTrue(Files.exists(resourcesDir.resolve("kommando-luftwaffe.html")), "kommando-luftwaffe.html doit exister");
        assertTrue(Files.exists(resourcesDir.resolve("sample-vita.pdf")), "sample-vita.pdf doit exister");

        Path outputFile = tempDir.resolve("output.ndjson");
        Path rawDir = tempDir.resolve("raw");
        Files.createDirectories(rawDir);

        ParserContext ctx = new ParserContext("de_pep_defence_luftwaffe", resourcesDir.resolve("kommando-luftwaffe.html"), rawDir, outputFile, Map.of());
        DePepDefenceLuftwaffeParser parser = new DePepDefenceLuftwaffeParser();

        ParserResult result = parser.parse(ctx);
        assertNotNull(result);
        assertTrue(Files.exists(outputFile));
        assertEquals(0, result.conflictCount(), "Aucun conflit d'ID");
        assertEquals(0, result.rejectedCount(), "Aucune valeur rejetée par le modèle FtM");

        List<FtmEntity> entities = result.entities();
        assertFalse(entities.isEmpty());

        // Positions: Inspekteur der Luftwaffe, Stellvertreter, TLG 31, Luftfahrtamt
        assertTrue(entities.stream().anyMatch(e -> "Position".equals(e.getSchema())
                && e.getValues("name").contains("Inspekteur der Luftwaffe")));
        assertTrue(entities.stream().anyMatch(e -> "Position".equals(e.getSchema())
                && e.getValues("name").contains("Stellvertreter des Inspekteurs der Luftwaffe")));
        assertTrue(entities.stream().anyMatch(e -> "Position".equals(e.getSchema())
                && e.getValues("name").contains("Kommodore Taktisches Luftwaffengeschwader 31 \"Boelcke\"")));
        assertTrue(entities.stream().anyMatch(e -> "Position".equals(e.getSchema())
                && e.getValues("name").contains("Amtschef Luftfahrtamt der Bundeswehr")));

        // 1. Ingo Gerhartz (Generalleutnant - Inspekteur)
        FtmEntity gerhartz = entities.stream()
                .filter(e -> "Person".equals(e.getSchema()) && e.getValues("name").contains("Ingo Gerhartz"))
                .findFirst().orElseThrow();
        assertEquals("de", gerhartz.getValues("country").iterator().next());
        assertEquals("de", gerhartz.getValues("nationality").iterator().next());
        assertEquals("Generalleutnant", gerhartz.getValues("title").iterator().next());
        assertTrue(gerhartz.getValues("topics").contains("role.pep"));

        FtmEntity gerhartzOcc = entities.stream()
                .filter(e -> "Occupancy".equals(e.getSchema()) && e.getValues("holder").contains(gerhartz.getId()))
                .findFirst().orElseThrow();
        assertEquals("current", gerhartzOcc.getValues("status").iterator().next());
        assertEquals("2018-05-01", gerhartzOcc.getValues("startDate").iterator().next());

        // 2. Lutz Kohlhaus (Generalleutnant - Stellvertreter)
        FtmEntity kohlhaus = entities.stream()
                .filter(e -> "Person".equals(e.getSchema()) && e.getValues("name").contains("Lutz Kohlhaus"))
                .findFirst().orElseThrow();
        assertEquals("Generalleutnant", kohlhaus.getValues("title").iterator().next());

        FtmEntity kohlhausOcc = entities.stream()
                .filter(e -> "Occupancy".equals(e.getSchema()) && e.getValues("holder").contains(kohlhaus.getId()))
                .findFirst().orElseThrow();
        assertEquals("2023-07-01", kohlhausOcc.getValues("startDate").iterator().next());

        // 3. Jens Schumacher (Oberst - TLG 31 Boelcke)
        FtmEntity schumacher = entities.stream()
                .filter(e -> "Person".equals(e.getSchema()) && e.getValues("name").contains("Jens Schumacher"))
                .findFirst().orElseThrow();
        assertEquals("Oberst", schumacher.getValues("title").iterator().next());

        FtmEntity schumacherOcc = entities.stream()
                .filter(e -> "Occupancy".equals(e.getSchema()) && e.getValues("holder").contains(schumacher.getId()))
                .findFirst().orElseThrow();
        assertEquals("2021-10-01", schumacherOcc.getValues("startDate").iterator().next());

        // 4. Christian Havenith (Brigadegeneral - Luftfahrtamt)
        FtmEntity havenith = entities.stream()
                .filter(e -> "Person".equals(e.getSchema()) && e.getValues("name").contains("Christian Havenith"))
                .findFirst().orElseThrow();
        assertEquals("Brigadegeneral", havenith.getValues("title").iterator().next());

        FtmEntity havenithOcc = entities.stream()
                .filter(e -> "Occupancy".equals(e.getSchema()) && e.getValues("holder").contains(havenith.getId()))
                .findFirst().orElseThrow();
        assertEquals("2024-04-01", havenithOcc.getValues("startDate").iterator().next());

        // Source records
        List<SourceRecord> records = parser.readSourceRecords(ctx);
        assertEquals(4, records.size(), "4 commandants et officiers généraux doivent être extraits");
    }

    @Test
    void testParseGermanMonthYear() {
        assertEquals("2018-05-01", DePepDefenceLuftwaffeParser.parseGermanMonthYear("Mai 2018"));
        assertEquals("2023-07-01", DePepDefenceLuftwaffeParser.parseGermanMonthYear("Juli 2023"));
        assertEquals("2021-10-01", DePepDefenceLuftwaffeParser.parseGermanMonthYear("Oktober 2021"));
        assertEquals("2024-04-01", DePepDefenceLuftwaffeParser.parseGermanMonthYear("April 2024"));
        assertEquals("2025-01-01", DePepDefenceLuftwaffeParser.parseGermanMonthYear("2025"));
        assertEquals("2024-09-01", DePepDefenceLuftwaffeParser.parseGermanMonthYear("09/2024"));
        assertNull(DePepDefenceLuftwaffeParser.parseGermanMonthYear(null));
    }

    @Test
    void testSha256Computation() {
        byte[] data = "Bundeswehr Luftwaffe Test".getBytes(StandardCharsets.UTF_8);
        String hash = DePepDefenceLuftwaffeParser.computeSha256(data);
        assertNotNull(hash);
        assertEquals(64, hash.length(), "Le hash SHA-256 doit comporter 64 caractères hexadécimaux");
    }

    @Test
    void testRequireParameterWhenMissingInLiveMode(@TempDir Path tempDir) {
        Path outputFile = tempDir.resolve("output.ndjson");
        Path rawDir = tempDir.resolve("raw");
        ParserContext ctx = new ParserContext("de_pep_defence_luftwaffe", null, rawDir, outputFile, Map.of());
        DePepDefenceLuftwaffeParser parser = new DePepDefenceLuftwaffeParser();

        assertThrows(IllegalArgumentException.class, () -> parser.parse(ctx),
                "Doit lever IllegalArgumentException si kommandoLuftwaffeUrl manque dans ParserContext");
    }
}
