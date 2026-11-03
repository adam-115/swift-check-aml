package com.swiftcheck.aml.plugins.depep.diplomats;

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

class DePepDiplomatsParserTest {

    private static final String MOCK_DIPLOMATS = """
            # Liste der Leiterinnen und Leiter der deutschen Auslandsvertretungen
            Frankreich | Paris | Botschafter | Stephan Steinlein
            Vereinigte Staaten | Washington | Botschafterin | Andreas Michaelis
            Vereinigtes Königreich | London | Botschafter | Miguel Berger
            Belgien | Brüssel (Ständige Vertretung EU) | Ständiger Vertreter | Michael Clauss
            Mali | Bamako | Geschäftsträger a.i. | Dr. Philipp Brenner
            """;

    @Test
    void testParseDiplomats(@TempDir Path tempDir) throws Exception {
        Path inputFile = tempDir.resolve("diplomats.txt");
        Files.writeString(inputFile, MOCK_DIPLOMATS);
        Path outputFile = tempDir.resolve("output.ndjson");

        ParserContext ctx = new ParserContext("de_pep_diplomats", inputFile, tempDir, outputFile, Map.of());
        DePepDiplomatsParser parser = new DePepDiplomatsParser();

        ParserResult result = parser.parse(ctx);
        assertThat(result.message()).contains("5 ambassadeurs et chefs de poste");

        List<String> lines = Files.readAllLines(outputFile);
        assertThat(lines).isNotEmpty();

        // Check persons
        assertThat(lines).anyMatch(l -> l.contains("\"name\":[\"Stephan Steinlein\"]") && l.contains("role.pep") && l.contains("role.diplo"));
        assertThat(lines).anyMatch(l -> l.contains("\"name\":[\"Andreas Michaelis\"]"));
        assertThat(lines).anyMatch(l -> l.contains("\"name\":[\"Miguel Berger\"]"));
        assertThat(lines).anyMatch(l -> l.contains("\"name\":[\"Michael Clauss\"]"));
        assertThat(lines).anyMatch(l -> l.contains("\"name\":[\"Philipp Brenner\"]") && l.contains("\"title\":[\"Dr.\"]"));

        // Check positions
        assertThat(lines).anyMatch(l -> l.contains("\"schema\":\"Position\"") && l.contains("Botschafter der Bundesrepublik Deutschland (Frankreich)"));

        // Check source records
        List<SourceRecord> records = parser.readSourceRecords(ctx);
        assertThat(records).hasSize(5);
    }
}
