package com.swiftcheck.aml.plugins.depep.auditcourts;

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

class DePepAuditCourtsParserTest {

    private static final String MOCK_BRH_HTML = """
            <!DOCTYPE html>
            <html>
            <body>
              <div class="teaser teaser--person">
                <img src="/foto-ansgar-heveling.jpg" alt="Ansgar Heveling" />
                <h3 class="teaser__heading">
                  <a href="/heveling.html">Ansgar Heveling</a>
                </h3>
                <p class="teaser__role">Präsident des Bundesrechnungshofes</p>
              </div>
              <div class="teaser teaser--person">
                <img src="/foto-vizepraesidentin.jpg" alt="Dr. Christiane Fischer" />
                <h3 class="teaser__heading">
                  <a href="/fischer.html">Dr. Christiane Fischer</a>
                </h3>
                <p class="teaser__role">Vizepräsidentin des Bundesrechnungshofes</p>
              </div>
              <div class="teaser teaser--person">
                <h3 class="teaser__heading">
                  <a href="/schmidt.html">Michael Schmidt</a>
                </h3>
                <p class="teaser__role">Mitglied des Kollegiums</p>
              </div>
            </body>
            </html>
            """;

    @Test
    void testParseAuditCourts(@TempDir Path tempDir) throws Exception {
        Path inputFile = tempDir.resolve("brh.html");
        Files.writeString(inputFile, MOCK_BRH_HTML);
        Path outputFile = tempDir.resolve("output.ndjson");

        ParserContext ctx = new ParserContext("de_pep_audit_courts", inputFile, tempDir, outputFile, Map.of());
        DePepAuditCourtsParser parser = new DePepAuditCourtsParser();

        ParserResult result = parser.parse(ctx);
        assertThat(result.message()).contains("3 dirigeants de cours des comptes");

        List<String> lines = Files.readAllLines(outputFile);
        assertThat(lines).isNotEmpty();

        // Check persons
        assertThat(lines).anyMatch(l -> l.contains("\"name\":[\"Ansgar Heveling\"]"));
        assertThat(lines).anyMatch(l -> l.contains("\"name\":[\"Christiane Fischer\"]") && l.contains("\"title\":[\"Dr.\"]"));
        assertThat(lines).anyMatch(l -> l.contains("\"name\":[\"Michael Schmidt\"]"));

        // Check positions
        assertThat(lines).anyMatch(l -> l.contains("\"schema\":\"Position\"") && l.contains("Mitglied des Bundesrechnungshofes"));

        // Check source records
        List<SourceRecord> records = parser.readSourceRecords(ctx);
        assertThat(records).hasSize(3);
    }
}

