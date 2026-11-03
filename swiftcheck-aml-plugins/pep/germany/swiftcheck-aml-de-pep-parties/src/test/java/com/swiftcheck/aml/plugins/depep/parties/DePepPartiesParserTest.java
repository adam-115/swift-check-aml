package com.swiftcheck.aml.plugins.depep.parties;

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

class DePepPartiesParserTest {

    private static final String MOCK_SPD_FILING = """
            # Unterlagensammlung Bundeswahlleiterin - SPD Parteivorstand
            Vorsitzende: Saskia Esken, Lars Klingbeil
            Stellvertretende Vorsitzende: Klara Geywitz, Hubertus Heil, Achim Post
            Generalsekretär: Dr. Matthias Miersch
            Schatzmeister: Dietmar Nietan
            """;

    @Test
    void testParseParties(@TempDir Path tempDir) throws Exception {
        Path inputFile = tempDir.resolve("spd.txt");
        Files.writeString(inputFile, MOCK_SPD_FILING);
        Path outputFile = tempDir.resolve("output.ndjson");

        ParserContext ctx = new ParserContext("de_pep_parties", inputFile, tempDir, outputFile, Map.of());
        DePepPartiesParser parser = new DePepPartiesParser();

        ParserResult result = parser.parse(ctx);
        assertThat(result.message()).contains("7 membres des organes dirigeants");

        List<String> lines = Files.readAllLines(outputFile);
        assertThat(lines).isNotEmpty();

        // Check persons & political parties
        assertThat(lines).anyMatch(l -> l.contains("\"name\":[\"Saskia Esken\"]") && l.contains("\"political\":[\"SPD\"]"));
        assertThat(lines).anyMatch(l -> l.contains("\"name\":[\"Lars Klingbeil\"]"));
        assertThat(lines).anyMatch(l -> l.contains("\"name\":[\"Matthias Miersch\"]") && l.contains("\"title\":[\"Dr.\"]"));

        // Check positions
        assertThat(lines).anyMatch(l -> l.contains("\"schema\":\"Position\"") && l.contains("Mitglied des Bundesvorstands (SPD)"));

        // Check source records
        List<SourceRecord> records = parser.readSourceRecords(ctx);
        assertThat(records).hasSize(7);
    }
}

