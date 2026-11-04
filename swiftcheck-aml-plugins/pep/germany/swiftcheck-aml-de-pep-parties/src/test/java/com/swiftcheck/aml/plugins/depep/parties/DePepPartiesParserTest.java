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
            Partei: SPD
            Vorsitzende: Saskia Esken, Lars Klingbeil
            Stellvertretende Vorsitzende: Klara Geywitz, Hubertus Heil, Achim Post
            Generalsekretär: Dr. Matthias Miersch
            Schatzmeister: Dietmar Nietan
            """;

    private static final String MOCK_WIKIDATA_JSON = """
            {
              "head": {
                "vars": [ "party", "partyLabel", "role", "statement", "rank", "start", "end", "person", "personLabel", "birth", "birthPrecision", "gender" ]
              },
              "results": {
                "bindings": [
                  {
                    "party": { "value": "http://www.wikidata.org/entity/Q49768" },
                    "partyLabel": { "value": "Sozialdemokratische Partei Deutschlands" },
                    "role": { "value": "chair" },
                    "statement": { "value": "http://www.wikidata.org/entity/statement/Q49768-s1" },
                    "rank": { "value": "http://wikiba.se/ontology#PreferredRank" },
                    "start": { "value": "2019-12-06T00:00:00Z" },
                    "person": { "value": "http://www.wikidata.org/entity/Q1670308" },
                    "personLabel": { "value": "Saskia Esken" },
                    "birth": { "value": "1961-08-28T00:00:00Z" },
                    "birthPrecision": { "value": "11" },
                    "gender": { "value": "http://www.wikidata.org/entity/Q6581072" }
                  },
                  {
                    "party": { "value": "http://www.wikidata.org/entity/Q49762" },
                    "partyLabel": { "value": "Christlich Demokratische Union Deutschlands" },
                    "role": { "value": "chair" },
                    "statement": { "value": "http://www.wikidata.org/entity/statement/Q49762-s1" },
                    "rank": { "value": "http://wikiba.se/ontology#PreferredRank" },
                    "start": { "value": "2022-01-31T00:00:00Z" },
                    "person": { "value": "http://www.wikidata.org/entity/Q9012" },
                    "personLabel": { "value": "Friedrich Merz" },
                    "birth": { "value": "1955-11-11T00:00:00Z" },
                    "birthPrecision": { "value": "11" },
                    "gender": { "value": "http://www.wikidata.org/entity/Q6581097" }
                  }
                ]
              }
            }
            """;

    @Test
    void testParseWikidataLiveResponse(@TempDir Path tempDir) throws Exception {
        Path outputFile = tempDir.resolve("output.ndjson");

        ParserContext ctx = new ParserContext("de_pep_parties", null, tempDir, outputFile, Map.of());
        // le téléchargement est simulé : le plugin récupère toujours la réponse en direct, sans fichier laissé dans rawDir
        DePepPartiesParser parser = new DePepPartiesParser((endpoint, country, raw) -> Files.writeString(raw.resolve("leaders.json"), MOCK_WIKIDATA_JSON));

        ParserResult result = parser.parse(ctx);
        assertThat(result.message()).contains("partis politiques allemands dans Wikidata");

        List<String> lines = Files.readAllLines(outputFile);
        assertThat(lines).isNotEmpty();

        assertThat(lines).anyMatch(l -> l.contains("Saskia Esken") && l.contains("Q1670308"));
        assertThat(lines).anyMatch(l -> l.contains("Friedrich Merz") && l.contains("Q9012"));

        List<SourceRecord> records = parser.readSourceRecords(ctx);
        assertThat(records).hasSize(2);
    }

    @Test
    void testParseFailureWhenNoLiveResponse(@TempDir Path tempDir) {
        Path outputFile = tempDir.resolve("output.ndjson");

        ParserContext ctx = new ParserContext("de_pep_parties", null, tempDir, outputFile,
                Map.of("endpoint", "http://127.0.0.1:1/invalid"));
        DePepPartiesParser parser = new DePepPartiesParser();

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> parser.parse(ctx));
    }

    @Test
    void testParseCustomInputFile(@TempDir Path tempDir) throws Exception {
        Path inputFile = tempDir.resolve("spd.txt");
        Files.writeString(inputFile, MOCK_SPD_FILING);
        Path outputFile = tempDir.resolve("output.ndjson");

        ParserContext ctx = new ParserContext("de_pep_parties", inputFile, tempDir, outputFile, Map.of());
        DePepPartiesParser parser = new DePepPartiesParser();

        ParserResult result = parser.parse(ctx);
        assertThat(result.message()).contains("dirigeants de partis politiques extraits");

        List<String> lines = Files.readAllLines(outputFile);
        assertThat(lines).isNotEmpty();
        assertThat(lines).anyMatch(l -> l.contains("Saskia Esken"));
        assertThat(lines).anyMatch(l -> l.contains("Matthias Miersch") && l.contains("Dr."));

        List<SourceRecord> records = parser.readSourceRecords(ctx);
        assertThat(records).hasSize(7);
    }
}
