package com.swiftcheck.aml.plugins.depep.federalcourts;

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

class DePepFederalCourtsParserTest {

    private static final String MOCK_BVERFG_HTML = """
            <!DOCTYPE html>
            <html>
            <body>
              <div class="teaser teaser--person">
                <img src="/foto-harbarth.jpg" alt="Prof. Dr. Stephan Harbarth" />
                <h3 class="teaser__heading">
                  <a href="/harbarth.html">Prof. Dr. Stephan Harbarth</a>
                </h3>
                <p class="teaser__role">Präsident des Bundesverfassungsgerichts</p>
              </div>
              <div class="teaser teaser--person">
                <img src="/foto-koenig.jpg" alt="Prof. Dr. Doris König" />
                <h3 class="teaser__heading">
                  <a href="/koenig.html">Prof. Dr. Doris König</a>
                </h3>
                <p class="teaser__role">Vizepräsidentin des Bundesverfassungsgerichts</p>
              </div>
              <div class="teaser teaser--person">
                <h3 class="teaser__heading">
                  <a href="/radtke.html">Prof. Dr. Henning Radtke</a>
                </h3>
                <p class="teaser__role">Richter des Bundesverfassungsgerichts</p>
              </div>
            </body>
            </html>
            """;

    @Test
    void testParseFederalCourts(@TempDir Path tempDir) throws Exception {
        Path inputFile = tempDir.resolve("bverfg.html");
        Files.writeString(inputFile, MOCK_BVERFG_HTML);
        Path outputFile = tempDir.resolve("output.ndjson");

        ParserContext ctx = new ParserContext("de_pep_federal_courts", inputFile, tempDir, outputFile, Map.of());
        DePepFederalCourtsParser parser = new DePepFederalCourtsParser();

        ParserResult result = parser.parse(ctx);
        assertThat(result.message()).contains("3 juges des cours fédérales");

        List<String> lines = Files.readAllLines(outputFile);
        assertThat(lines).isNotEmpty();

        // Check persons
        assertThat(lines).anyMatch(l -> l.contains("\"name\":[\"Stephan Harbarth\"]") && l.contains("\"title\":[\"Prof. Dr.\"]"));
        assertThat(lines).anyMatch(l -> l.contains("\"name\":[\"Doris König\"]") && l.contains("\"title\":[\"Prof. Dr.\"]"));
        assertThat(lines).anyMatch(l -> l.contains("\"name\":[\"Henning Radtke\"]"));

        // Check positions
        assertThat(lines).anyMatch(l -> l.contains("\"schema\":\"Position\"") && l.contains("Richter am Bundesverfassungsgericht"));

        // Check source records
        List<SourceRecord> records = parser.readSourceRecords(ctx);
        assertThat(records).hasSize(3);
    }
}

