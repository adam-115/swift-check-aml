package com.swiftcheck.aml.plugins.depep.landconstitutionalcourts;

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

class DePepLandConstitutionalCourtsParserTest {

    private static final String MOCK_VGH_BW_HTML = """
            <!DOCTYPE html>
            <html>
            <body>
              <div class="teaser teaser--person">
                <img src="/foto-malte-graff.jpg" alt="Prof. Dr. Malte Graßhof" />
                <h3 class="teaser__heading">
                  <a href="/grasshof.html">Prof. Dr. Malte Graßhof</a>
                </h3>
                <p class="teaser__role">Präsident des Verfassungsgerichtshofs</p>
              </div>
              <div class="teaser teaser--person">
                <h3 class="teaser__heading">
                  <a href="/storz.html">Dr. Claudia Storz</a>
                </h3>
                <p class="teaser__role">Vizepräsidentin des Verfassungsgerichtshofs</p>
              </div>
              <div class="teaser teaser--person">
                <h3 class="teaser__heading">
                  <a href="/bauer.html">Dr. Klaus Bauer</a>
                </h3>
                <p class="teaser__role">Mitglied des Verfassungsgerichtshofs</p>
              </div>
            </body>
            </html>
            """;

    @Test
    void testParseLandConstitutionalCourts(@TempDir Path tempDir) throws Exception {
        Path inputFile = tempDir.resolve("vgh-bw.html");
        Files.writeString(inputFile, MOCK_VGH_BW_HTML);
        Path outputFile = tempDir.resolve("output.ndjson");

        ParserContext ctx = new ParserContext("de_pep_land_constitutional_courts", inputFile, tempDir, outputFile, Map.of());
        DePepLandConstitutionalCourtsParser parser = new DePepLandConstitutionalCourtsParser();

        ParserResult result = parser.parse(ctx);
        assertThat(result.message()).contains("3 juges des cours constitutionnelles");

        List<String> lines = Files.readAllLines(outputFile);
        assertThat(lines).isNotEmpty();

        // Check persons
        assertThat(lines).anyMatch(l -> l.contains("Graßhof") && l.contains("Prof. Dr."));
        assertThat(lines).anyMatch(l -> l.contains("Claudia Storz") && l.contains("Dr."));
        assertThat(lines).anyMatch(l -> l.contains("Klaus Bauer"));

        // Check positions
        assertThat(lines).anyMatch(l -> l.contains("\"schema\":\"Position\"") && l.contains("Verfassungsrichter (Baden-Württemberg)"));

        // Check source records
        List<SourceRecord> records = parser.readSourceRecords(ctx);
        assertThat(records).hasSize(3);
    }
}

