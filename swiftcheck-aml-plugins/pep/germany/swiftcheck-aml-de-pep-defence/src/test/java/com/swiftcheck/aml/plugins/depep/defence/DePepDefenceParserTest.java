package com.swiftcheck.aml.plugins.depep.defence;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.plugins.depep.defence.DePepDefenceParser.MinisterData;
import com.swiftcheck.aml.plugins.depep.defence.DePepDefenceParser.ScrapeResult;
import com.swiftcheck.aml.sdk.FtmEntity;

public class DePepDefenceParserTest {

    @TempDir
    Path tempDir;

    @Test
    void testParseBmvgOfflineResources() throws Exception {
        Path resourcesDir = Path.of("src/test/resources/bmvg");
        assertTrue(Files.exists(resourcesDir.resolve("vita.html")), "vita.html doit exister");
        assertTrue(Files.exists(resourcesDir.resolve("generalinspekteur.html")), "generalinspekteur.html doit exister");
        assertTrue(Files.exists(resourcesDir.resolve("ehemalige.html")), "ehemalige.html doit exister");

        Path outputFile = tempDir.resolve("output.ndjson");
        Path rawDir = tempDir.resolve("raw");
        Files.createDirectories(rawDir);

        ParserContext ctx = new ParserContext("de_pep_defence", resourcesDir.resolve("ehemalige.html"), rawDir, outputFile, Map.of());
        DePepDefenceParser parser = new DePepDefenceParser();

        ParserResult result = parser.parse(ctx);
        assertNotNull(result);
        assertTrue(Files.exists(outputFile));
        assertEquals(0, result.conflictCount(), "Aucun conflit d'ID");
        assertEquals(0, result.rejectedCount(), "Aucune valeur rejetée par le schéma FtM");

        List<FtmEntity> entities = result.entities();
        assertFalse(entities.isEmpty());

        // Positions
        assertTrue(entities.stream().anyMatch(e -> "Position".equals(e.getSchema())
                && e.getValues("name").contains("Bundesminister der Verteidigung")));
        assertTrue(entities.stream().anyMatch(e -> "Position".equals(e.getSchema())
                && e.getValues("name").contains("Generalinspekteur der Bundeswehr")));

        // Boris Pistorius (A.1)
        FtmEntity pistorius = entities.stream()
                .filter(e -> "Person".equals(e.getSchema()) && e.getValues("name").contains("Boris Pistorius"))
                .findFirst()
                .orElse(null);
        assertNotNull(pistorius, "Boris Pistorius doit être extrait");
        assertEquals("de", pistorius.getValues("country").iterator().next());
        assertEquals("de", pistorius.getValues("nationality").iterator().next());
        assertEquals("1960-03-14", pistorius.getValues("birthDate").iterator().next());
        assertTrue(pistorius.getValues("birthPlace").contains("Osnabrück"));
        assertTrue(pistorius.getValues("political").contains("SPD"));
        assertTrue(pistorius.getValues("topics").contains("role.pep"));

        // General Carsten Breuer (F.1)
        FtmEntity breuer = entities.stream()
                .filter(e -> "Person".equals(e.getSchema()) && e.getValues("name").contains("Carsten Breuer"))
                .findFirst()
                .orElse(null);
        assertNotNull(breuer, "General Carsten Breuer doit être extrait");
        assertEquals("General", breuer.getValues("title").iterator().next());
        assertTrue(breuer.getValues("topics").contains("role.pep"));

        // Occupancy for Breuer
        FtmEntity breuerOcc = entities.stream()
                .filter(e -> "Occupancy".equals(e.getSchema()) && e.getValues("holder").contains(breuer.getId()))
                .findFirst()
                .orElse(null);
        assertNotNull(breuerOcc);
        assertEquals("current", breuerOcc.getValues("status").iterator().next());
        assertEquals("2023-03-17", breuerOcc.getValues("startDate").iterator().next());

        // Former ministers (A.2)
        assertTrue(entities.stream().anyMatch(e -> "Person".equals(e.getSchema()) && e.getValues("name").contains("Christine Lambrecht")));
        assertTrue(entities.stream().anyMatch(e -> "Person".equals(e.getSchema()) && e.getValues("name").contains("Ursula von der Leyen")));
        assertTrue(entities.stream().anyMatch(e -> "Person".equals(e.getSchema()) && e.getValues("name").contains("Karl-Theodor zu Guttenberg")));
        assertTrue(entities.stream().anyMatch(e -> "Person".equals(e.getSchema()) && e.getValues("name").contains("Theodor Blank")));
        assertTrue(entities.stream().anyMatch(e -> "Person".equals(e.getSchema()) && e.getValues("name").contains("Helmut Schmidt")));

        // Check Lambrecht occupancy is ended
        FtmEntity lambrecht = entities.stream()
                .filter(e -> "Person".equals(e.getSchema()) && e.getValues("name").contains("Christine Lambrecht"))
                .findFirst().orElseThrow();
        FtmEntity lambrechtOcc = entities.stream()
                .filter(e -> "Occupancy".equals(e.getSchema()) && e.getValues("holder").contains(lambrecht.getId()))
                .findFirst().orElseThrow();
        assertEquals("ended", lambrechtOcc.getValues("status").iterator().next());
        assertEquals("2021-01-01", lambrechtOcc.getValues("startDate").iterator().next());
        assertEquals("2023-12-31", lambrechtOcc.getValues("endDate").iterator().next());

        // Read source records
        List<SourceRecord> records = parser.readSourceRecords(ctx);
        assertEquals(21, records.size(), "1 ministre actuel + 1 Generalinspekteur + 19 anciens ministres = 21 records");
    }

    @Test
    void testExtractMethodsIndividually() throws Exception {
        Path resourcesDir = Path.of("src/test/resources/bmvg");
        String vitaHtml = Files.readString(resourcesDir.resolve("vita.html"));
        String genHtml = Files.readString(resourcesDir.resolve("generalinspekteur.html"));
        String ehemHtml = Files.readString(resourcesDir.resolve("ehemalige.html"));

        List<String> warnings = new ArrayList<>();

        MinisterData min = DePepDefenceParser.extractCurrentMinister(vitaHtml, "https://test.url/vita", warnings);
        assertNotNull(min);
        assertEquals("Boris Pistorius", min.name());
        assertEquals("1960-03-14", min.birthDate());
        assertEquals("2023-01-19", min.startDate());
        assertEquals("A.1", min.gwgClass());

        MinisterData gen = DePepDefenceParser.extractGeneralinspekteur(genHtml, "https://test.url/gen", warnings);
        assertNotNull(gen);
        assertEquals("Carsten Breuer", gen.name());
        assertEquals("2023-03-17", gen.startDate());
        assertEquals("F.1", gen.gwgClass());

        List<MinisterData> formers = DePepDefenceParser.extractFormerMinisters(ehemHtml, "https://test.url/ehem", warnings);
        assertEquals(19, formers.size());
        for (MinisterData f : formers) {
            assertEquals("A.2", f.gwgClass());
            assertEquals("ended", f.status());
            assertNotNull(f.startDate());
            assertNotNull(f.endDate());
        }
    }

    @Test
    void testSlugifyAndCleanText() {
        assertEquals("boris-pistorius", DePepDefenceParser.slugify("Boris Pistorius"));
        assertEquals("carsten-breuer", DePepDefenceParser.slugify("Carsten Breuer"));
        assertEquals("thomas-de-maiziere", DePepDefenceParser.slugify("Thomas de Maizière"));
        assertEquals("franz-josef-strauss", DePepDefenceParser.slugify("Franz Josef Strauß"));
        assertEquals("Bonjour le monde", DePepDefenceParser.cleanText("<p>Bonjour&nbsp;<b>le</b> monde</p>"));
    }
}
