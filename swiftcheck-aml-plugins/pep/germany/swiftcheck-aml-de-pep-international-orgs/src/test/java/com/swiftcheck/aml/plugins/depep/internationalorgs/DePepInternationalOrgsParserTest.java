package com.swiftcheck.aml.plugins.depep.internationalorgs;

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

class DePepInternationalOrgsParserTest {

    private static final String MOCK_EPO_TEXT = """
            # Leitung des Europäischen Patentamts (EPA / EPO)
            Präsident des EPA: António Campinos
            Vizepräsident des EPA: Stephen Rowan, Christoph Ernst, Nellie Simon
            """;

    @Test
    void testParseInternationalOrgs(@TempDir Path tempDir) throws Exception {
        Path inputFile = tempDir.resolve("epo.txt");
        Files.writeString(inputFile, MOCK_EPO_TEXT);
        Path outputFile = tempDir.resolve("output.ndjson");

        ParserContext ctx = new ParserContext("de_pep_international_orgs", inputFile, tempDir, outputFile, Map.of());
        DePepInternationalOrgsParser parser = new DePepInternationalOrgsParser();

        ParserResult result = parser.parse(ctx);
        assertThat(result.message()).contains("4 dirigeants d'organisations internationales");

        List<String> lines = Files.readAllLines(outputFile);
        assertThat(lines).isNotEmpty();

        // Check persons
        assertThat(lines).anyMatch(l -> l.contains("\"name\":[\"António Campinos\"]"));
        assertThat(lines).anyMatch(l -> l.contains("\"name\":[\"Stephen Rowan\"]"));
        assertThat(lines).anyMatch(l -> l.contains("\"name\":[\"Christoph Ernst\"]"));
        assertThat(lines).anyMatch(l -> l.contains("\"name\":[\"Nellie Simon\"]"));

        // Check positions
        assertThat(lines).anyMatch(l -> l.contains("\"schema\":\"Position\"") && l.contains("Europäisches Patentamt"));

        // Check source records
        List<SourceRecord> records = parser.readSourceRecords(ctx);
        assertThat(records).hasSize(4);
    }

    @Test
    void testParseInternationalOrgsBaselineFallback(@TempDir Path tempDir) throws Exception {
        Path outputFile = tempDir.resolve("output.ndjson");
        ParserContext ctx = new ParserContext("de_pep_international_orgs", null, tempDir, outputFile, Map.of());
        DePepInternationalOrgsParser parser = new DePepInternationalOrgsParser();

        ParserResult result = parser.parse(ctx);
        assertThat(result.message()).contains("dirigeants d'organisations internationales en Allemagne extraits");

        List<String> lines = Files.readAllLines(outputFile);
        assertThat(lines).isNotEmpty();
        assertThat(lines).anyMatch(l -> l.contains("António Campinos"));
        assertThat(lines).anyMatch(l -> l.contains("Simon Stiell"));

        assertThat(Files.exists(tempDir.resolve("international-orgs.json"))).isTrue();

        List<SourceRecord> records = parser.readSourceRecords(ctx);
        assertThat(records).isNotEmpty();
    }
}

