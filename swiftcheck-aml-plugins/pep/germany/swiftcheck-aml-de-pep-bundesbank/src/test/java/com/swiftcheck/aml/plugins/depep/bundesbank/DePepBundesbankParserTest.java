package com.swiftcheck.aml.plugins.depep.bundesbank;

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

class DePepBundesbankParserTest {

    private static final String MOCK_HTML = """
            <!DOCTYPE html>
            <html>
            <body>
              <div class="teaser teaser--portrait">
                <img src="/resource/blob/883492/foto-joachim-nagel.jpg" alt="Dr. Joachim Nagel" />
                <h3 class="teaser__heading">
                  <a href="/de/bundesbank/organisation/vorstand/dr-joachim-nagel-883494">Dr. Joachim Nagel</a>
                </h3>
                <p class="teaser__role">Präsident der Deutschen Bundesbank</p>
              </div>
              <div class="teaser teaser--portrait">
                <img src="/resource/blob/883496/foto-sabine-mauderer.jpg" alt="Prof. Dr. Sabine Mauderer" />
                <h3 class="teaser__heading">
                  <a href="/de/bundesbank/organisation/vorstand/prof-dr-sabine-mauderer-883498">Prof. Dr. Sabine Mauderer</a>
                </h3>
                <p class="teaser__role">Vizepräsidentin der Deutschen Bundesbank</p>
              </div>
              <div class="teaser teaser--portrait">
                <img src="/resource/blob/901234/foto-michael-theurer.jpg" alt="Michael Theurer" />
                <h3 class="teaser__heading">
                  <a href="/de/bundesbank/organisation/vorstand/michael-theurer-901236">Michael Theurer</a>
                </h3>
                <p class="teaser__role">Mitglied des Vorstands der Deutschen Bundesbank</p>
              </div>
              <div class="teaser teaser--portrait">
                <img src="/resource/blob/901238/foto-lutz-lienenkaemper.jpg" alt="Lutz Lienenkämper" />
                <h3 class="teaser__heading">
                  <a href="/de/bundesbank/organisation/vorstand/lutz-lienenkaemper-901240">Lutz Lienenkämper</a>
                </h3>
                <p class="teaser__role">Mitglied des Vorstands der Deutschen Bundesbank</p>
              </div>
            </body>
            </html>
            """;

    @Test
    void testParseBundesbank(@TempDir Path tempDir) throws Exception {
        Path inputFile = tempDir.resolve("bundesbank_vorstand.html");
        Files.writeString(inputFile, MOCK_HTML);
        Path outputFile = tempDir.resolve("output.ndjson");

        ParserContext ctx = new ParserContext("de_pep_bundesbank", inputFile, tempDir, outputFile, Map.of());
        DePepBundesbankParser parser = new DePepBundesbankParser();

        ParserResult result = parser.parse(ctx);
        assertThat(result.message()).contains("4 membres du directoire");

        List<String> lines = Files.readAllLines(outputFile);
        assertThat(lines).isNotEmpty();

        // Check persons
        assertThat(lines).anyMatch(l -> l.contains("\"name\":[\"Joachim Nagel\"]") && l.contains("\"title\":[\"Dr.\"]"));
        assertThat(lines).anyMatch(l -> l.contains("\"name\":[\"Sabine Mauderer\"]") && l.contains("\"title\":[\"Prof. Dr.\"]"));
        assertThat(lines).anyMatch(l -> l.contains("\"name\":[\"Michael Theurer\"]"));
        assertThat(lines).anyMatch(l -> l.contains("Lienenk") && l.contains("Lutz"));

        // Check positions and occupancies
        assertThat(lines).anyMatch(l -> l.contains("\"schema\":\"Position\"") && l.contains("Mitglied des Vorstands der Deutschen Bundesbank"));
        assertThat(lines).anyMatch(l -> l.contains("\"schema\":\"Occupancy\""));

        // Check readSourceRecords
        List<SourceRecord> records = parser.readSourceRecords(ctx);
        assertThat(records).hasSize(4);
        assertThat(records.get(0).name()).isEqualTo("Dr. Joachim Nagel");
    }
}
