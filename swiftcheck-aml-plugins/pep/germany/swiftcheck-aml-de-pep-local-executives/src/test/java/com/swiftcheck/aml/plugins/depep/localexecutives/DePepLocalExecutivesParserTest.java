package com.swiftcheck.aml.plugins.depep.localexecutives;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;

import static org.assertj.core.api.Assertions.assertThat;

class DePepLocalExecutivesParserTest {

    private static final String MOCK_LOCAL_LINES = """
            # Kommunale Spitzenbeamte >= 50.000 Einwohner (Destatis / Kommunen)
            Köln | KREISFREIE_STADT | 1084831 | Oberbürgermeisterin | Henriette Reker | parteilos
            Frankfurt am Main | KREISFREIE_STADT | 773068 | Oberbürgermeister | Mike Josef | SPD
            Düsseldorf | KREISFREIE_STADT | 629047 | Oberbürgermeister | Dr. Stephan Keller | CDU
            Rhein-Neckar-Kreis | LANDKREIS | 549000 | Landrat | Stefan Dallinger | CDU
            Kleine Gemeinde | GEMEINDE | 12000 | Bürgermeister | Hans Müller | FW
            """;

    private static final String MOCK_JSON = """
            [
              { "city": "München", "type": "KREISFREIE_STADT", "population": 1488000, "role": "Oberbürgermeister", "name": "Dieter Reiter", "party": "SPD" },
              { "city": "Landkreis München", "type": "LANDKREIS", "population": 350000, "role": "Landrat", "name": "Christoph Göbel", "party": "CSU" }
            ]
            """;

    @Test
    void testParseFromLinesFilteredByPopulation(@TempDir Path tempDir) throws Exception {
        Path inputFile = tempDir.resolve("executives.txt");
        Files.writeString(inputFile, MOCK_LOCAL_LINES);
        Path outputFile = tempDir.resolve("output.ndjson");

        ParserContext ctx = new ParserContext("de_pep_local_executives", inputFile, tempDir, outputFile, Map.of("minPopulation", "50000"));
        DePepLocalExecutivesParser parser = new DePepLocalExecutivesParser();

        ParserResult result = parser.parse(ctx);
        // Kleine Gemeinde (12 000 hab) doit être écartée
        assertThat(result.message()).contains("4 maires et Landräte");

        List<String> lines = Files.readAllLines(outputFile);
        assertThat(lines).isNotEmpty();

        // Check persons & roles
        assertThat(lines).anyMatch(l -> l.contains("\"name\":[\"Henriette Reker\"]"));
        assertThat(lines).anyMatch(l -> l.contains("\"name\":[\"Mike Josef\"]") && l.contains("\"political\":[\"SPD\"]"));
        assertThat(lines).anyMatch(l -> l.contains("\"name\":[\"Stephan Keller\"]") && l.contains("\"title\":[\"Dr.\"]"));
        assertThat(lines).anyMatch(l -> l.contains("\"name\":[\"Stefan Dallinger\"]"));
        assertThat(lines).noneMatch(l -> l.contains("Hans Müller"));

        // Check position
        assertThat(lines).anyMatch(l -> l.contains("\"schema\":\"Position\"") && l.contains("Oberbürgermeisterin (Köln)"));

        // Check source records
        List<SourceRecord> records = parser.readSourceRecords(ctx);
        assertThat(records).hasSize(4);
    }

    @Test
    void testParseFromJson(@TempDir Path tempDir) throws Exception {
        Path inputFile = tempDir.resolve("executives.json");
        Files.writeString(inputFile, MOCK_JSON);
        Path outputFile = tempDir.resolve("output.ndjson");

        ParserContext ctx = new ParserContext("de_pep_local_executives", inputFile, tempDir, outputFile, Map.of("minPopulation", "50000"));
        DePepLocalExecutivesParser parser = new DePepLocalExecutivesParser();

        ParserResult result = parser.parse(ctx);
        assertThat(result.message()).contains("2 maires et Landräte");
    }
}

