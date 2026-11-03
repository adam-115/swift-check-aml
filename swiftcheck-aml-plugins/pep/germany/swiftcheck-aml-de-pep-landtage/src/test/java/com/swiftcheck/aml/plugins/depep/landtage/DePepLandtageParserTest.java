package com.swiftcheck.aml.plugins.depep.landtage;

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

class DePepLandtageParserTest {

    private static final String MOCK_LANDTAG_LINES = """
            # Mitglieder der Landtage (Landtag von Baden-Württemberg & Bayern)
            Baden-Württemberg | Winfried Kretschmann | GRÜNE | Wahlkreis Nürtingen
            Baden-Württemberg | Manuel Hagel | CDU | Wahlkreis Ehingen
            Bayern | Dr. Markus Söder | CSU | Stimmkreis Nürnberg-West
            Bayern | Katharina Schulze | GRÜNE | Stimmkreis München-Milbertshofen
            """;

    private static final String MOCK_JSON = """
            [
              { "first_name": "Winfried", "last_name": "Kretschmann", "party": "GRÜNE", "parliament": "Baden-Württemberg" },
              { "first_name": "Markus", "last_name": "Söder", "party": "CSU", "parliament": "Bayern" }
            ]
            """;

    @Test
    void testParseFromLines(@TempDir Path tempDir) throws Exception {
        Path inputFile = tempDir.resolve("landtage.txt");
        Files.writeString(inputFile, MOCK_LANDTAG_LINES);
        Path outputFile = tempDir.resolve("output.ndjson");

        ParserContext ctx = new ParserContext("de_pep_landtage", inputFile, tempDir, outputFile, Map.of());
        DePepLandtageParser parser = new DePepLandtageParser();

        ParserResult result = parser.parse(ctx);
        assertThat(result.message()).contains("4 députés des parlements régionaux");

        List<String> lines = Files.readAllLines(outputFile);
        assertThat(lines).isNotEmpty();

        // Check persons & parties
        assertThat(lines).anyMatch(l -> l.contains("\"name\":[\"Winfried Kretschmann\"]") && l.contains("\"political\":[\"GRÜNE\"]"));
        assertThat(lines).anyMatch(l -> l.contains("\"name\":[\"Markus Söder\"]") && l.contains("\"title\":[\"Dr.\"]") && l.contains("\"political\":[\"CSU\"]"));

        // Check position
        assertThat(lines).anyMatch(l -> l.contains("\"schema\":\"Position\"") && l.contains("Mitglied des Landtages (Baden-Württemberg)"));

        // Check source records
        List<SourceRecord> records = parser.readSourceRecords(ctx);
        assertThat(records).hasSize(4);
    }

    @Test
    void testParseFromJson(@TempDir Path tempDir) throws Exception {
        Path inputFile = tempDir.resolve("abgeordnetenwatch.json");
        Files.writeString(inputFile, MOCK_JSON);
        Path outputFile = tempDir.resolve("output.ndjson");

        ParserContext ctx = new ParserContext("de_pep_landtage", inputFile, tempDir, outputFile, Map.of());
        DePepLandtageParser parser = new DePepLandtageParser();

        ParserResult result = parser.parse(ctx);
        assertThat(result.message()).contains("2 députés des parlements régionaux");
    }
}

