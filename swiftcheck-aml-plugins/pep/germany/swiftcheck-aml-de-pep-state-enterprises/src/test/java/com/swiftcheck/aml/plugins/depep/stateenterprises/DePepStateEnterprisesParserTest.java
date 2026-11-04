package com.swiftcheck.aml.plugins.depep.stateenterprises;

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

class DePepStateEnterprisesParserTest {

    private static final String MOCK_REPORT = """
            # Beteiligungsbericht Auszug
            Unternehmen: Deutsche Bahn AG
            Vorstandsvorsitzender: Dr. Richard Lutz
            Aufsichtsratsvorsitzender: Werner Gatzer

            Unternehmen: KfW Bankengruppe
            Vorstandsvorsitzender: Stefan Wintels
            Verwaltungsratsvorsitzender: Dr. Robert Habeck
            """;

    @Test
    void testParseStateEnterprisesFromFile(@TempDir Path tempDir) throws Exception {
        Path inputFile = tempDir.resolve("beteiligungsbericht.txt");
        Files.writeString(inputFile, MOCK_REPORT);
        Path outputFile = tempDir.resolve("output.ndjson");

        ParserContext ctx = new ParserContext("de_pep_state_enterprises", inputFile, tempDir, outputFile, Map.of());
        DePepStateEnterprisesParser parser = new DePepStateEnterprisesParser();

        ParserResult result = parser.parse(ctx);
        assertThat(result.message()).contains("4 dirigeants d'entreprises publiques");

        List<String> lines = Files.readAllLines(outputFile);
        assertThat(lines).isNotEmpty();

        // Check persons
        assertThat(lines).anyMatch(l -> l.contains("\"name\":[\"Richard Lutz\"]") && l.contains("\"title\":[\"Dr.\"]"));
        assertThat(lines).anyMatch(l -> l.contains("\"name\":[\"Werner Gatzer\"]"));
        assertThat(lines).anyMatch(l -> l.contains("\"name\":[\"Stefan Wintels\"]"));
        assertThat(lines).anyMatch(l -> l.contains("\"name\":[\"Robert Habeck\"]"));

        // Check company
        assertThat(lines).anyMatch(l -> l.contains("\"schema\":\"Company\"") && l.contains("Deutsche Bahn AG"));

        // Check source records
        List<SourceRecord> records = parser.readSourceRecords(ctx);
        assertThat(records).hasSize(4);
    }

    @Test
    void testParseStateEnterprisesFromDefaultConfig(@TempDir Path tempDir) throws Exception {
        Path outputFile = tempDir.resolve("output.ndjson");

        ParserContext ctx = new ParserContext("de_pep_state_enterprises", null, tempDir, outputFile, Map.of());
        DePepStateEnterprisesParser parser = new DePepStateEnterprisesParser();

        ParserResult result = parser.parse(ctx);
        assertThat(result.message()).contains("dirigeants d'entreprises publiques");

        List<String> lines = Files.readAllLines(outputFile);
        assertThat(lines).isNotEmpty();
        assertThat(lines).anyMatch(l -> l.contains("Richard Lutz"));
        assertThat(Files.exists(tempDir.resolve("state-enterprises.json"))).isTrue();
    }
}

