package com.swiftcheck.aml.plugins.depep.defencesecretaries;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmEntity;

public class DePepDefenceSecretariesParserTest {

    @TempDir
    Path tempDir;

    @Test
    void testParseBmvgSecretariesOffline() throws Exception {
        Path resourcesDir = Path.of("src/test/resources/bmvg_sec");
        assertTrue(Files.exists(resourcesDir.resolve("hub.html")), "hub.html doit exister");
        assertTrue(Files.exists(resourcesDir.resolve("nils-hilmer.html")), "nils-hilmer.html doit exister");
        assertTrue(Files.exists(resourcesDir.resolve("jens-ploetner.html")), "jens-ploetner.html doit exister");
        assertTrue(Files.exists(resourcesDir.resolve("jan-stoess.html")), "jan-stoess.html doit exister");
        assertTrue(Files.exists(resourcesDir.resolve("sebastian-hartmann.html")), "sebastian-hartmann.html doit exister");
        assertTrue(Files.exists(resourcesDir.resolve("dr-nils-schmid.html")), "dr-nils-schmid.html doit exister");

        Path outputFile = tempDir.resolve("output.ndjson");
        Path rawDir = tempDir.resolve("raw");
        Files.createDirectories(rawDir);

        ParserContext ctx = new ParserContext("de_pep_defence_secretaries", resourcesDir.resolve("hub.html"), rawDir, outputFile, Map.of());
        DePepDefenceSecretariesParser parser = new DePepDefenceSecretariesParser();

        ParserResult result = parser.parse(ctx);
        assertNotNull(result);
        assertTrue(Files.exists(outputFile));
        assertEquals(0, result.conflictCount(), "Aucun conflit d'ID");
        assertEquals(0, result.rejectedCount(), "Aucune propriété rejetée par le schéma FtM");

        List<FtmEntity> entities = result.entities();
        assertFalse(entities.isEmpty());

        // Positions: Staatssekretär et Parlamentarischer Staatssekretär
        assertTrue(entities.stream().anyMatch(e -> "Position".equals(e.getSchema())
                && e.getValues("name").contains("Staatssekretär im Bundesministerium der Verteidigung")));
        assertTrue(entities.stream().anyMatch(e -> "Position".equals(e.getSchema())
                && e.getValues("name").contains("Parlamentarischer Staatssekretär beim Bundesminister der Verteidigung")));

        // 1. Nils Hilmer (Staatssekretär)
        FtmEntity hilmer = entities.stream()
                .filter(e -> "Person".equals(e.getSchema()) && e.getValues("name").contains("Nils Hilmer"))
                .findFirst().orElseThrow();
        assertEquals("de", hilmer.getValues("country").iterator().next());
        assertEquals("de", hilmer.getValues("nationality").iterator().next());
        assertTrue(hilmer.getValues("topics").contains("role.pep"));

        FtmEntity hilmerOcc = entities.stream()
                .filter(e -> "Occupancy".equals(e.getSchema()) && e.getValues("holder").contains(hilmer.getId()))
                .findFirst().orElseThrow();
        assertEquals("current", hilmerOcc.getValues("status").iterator().next());
        assertEquals("2023-03-01", hilmerOcc.getValues("startDate").iterator().next());

        // 2. Jens Plötner (Staatssekretär)
        FtmEntity ploetner = entities.stream()
                .filter(e -> "Person".equals(e.getSchema()) && e.getValues("name").contains("Jens Plötner"))
                .findFirst().orElseThrow();
        assertEquals("de", ploetner.getValues("country").iterator().next());

        // 3. Dr. Jan Stöß (Staatssekretär avec titre Dr.)
        FtmEntity stoess = entities.stream()
                .filter(e -> "Person".equals(e.getSchema()) && e.getValues("name").contains("Dr. Jan Stöß"))
                .findFirst().orElseThrow();
        assertEquals("Dr.", stoess.getValues("title").iterator().next());
        assertEquals("Jan", stoess.getValues("firstName").iterator().next());
        assertEquals("Stöß", stoess.getValues("lastName").iterator().next());

        // 4. Sebastian Hartmann (Parlamentarischer Staatssekretär)
        FtmEntity hartmann = entities.stream()
                .filter(e -> "Person".equals(e.getSchema()) && e.getValues("name").contains("Sebastian Hartmann"))
                .findFirst().orElseThrow();
        FtmEntity hartmannOcc = entities.stream()
                .filter(e -> "Occupancy".equals(e.getSchema()) && e.getValues("holder").contains(hartmann.getId()))
                .findFirst().orElseThrow();
        assertEquals("2025-05-01", hartmannOcc.getValues("startDate").iterator().next());

        // 5. Dr. Nils Schmid (Parlamentarischer Staatssekretär avec titre Dr.)
        FtmEntity schmid = entities.stream()
                .filter(e -> "Person".equals(e.getSchema()) && e.getValues("name").contains("Dr. Nils Schmid"))
                .findFirst().orElseThrow();
        assertEquals("Dr.", schmid.getValues("title").iterator().next());
        assertEquals("Nils", schmid.getValues("firstName").iterator().next());
        assertEquals("Schmid", schmid.getValues("lastName").iterator().next());

        // Read source records
        List<SourceRecord> records = parser.readSourceRecords(ctx);
        assertEquals(5, records.size(), "Les 5 secrétaires d'État doivent être extraits");
    }

    @Test
    void testExtractChildSlugsAndUrls() throws Exception {
        String hubHtml = Files.readString(Path.of("src/test/resources/bmvg_sec/hub.html"));
        Set<String> slugs = DePepDefenceSecretariesParser.extractChildSlugs(hubHtml);
        assertEquals(5, slugs.size());
        assertTrue(slugs.contains("nils-hilmer"));
        assertTrue(slugs.contains("jens-ploetner"));
        assertTrue(slugs.contains("jan-stoess"));
        assertTrue(slugs.contains("sebastian-hartmann"));
        assertTrue(slugs.contains("dr-nils-schmid"));

        Set<String> urls = DePepDefenceSecretariesParser.extractChildUrls(hubHtml);
        assertEquals(5, urls.size());
    }

    @Test
    void testParseStartDate() {
        assertEquals("2023-03-01", DePepDefenceSecretariesParser.parseStartDate("Seit 03.2023"));
        assertEquals("2025-05-01", DePepDefenceSecretariesParser.parseStartDate("seit 05/2025"));
        assertEquals("2023-03-17", DePepDefenceSecretariesParser.parseStartDate("seit 17.03.2023"));
        assertEquals("2025-01-01", DePepDefenceSecretariesParser.parseStartDate("im Jahr 2025"));
        assertNull(DePepDefenceSecretariesParser.parseStartDate(null));
    }
}

