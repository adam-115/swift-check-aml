package com.swiftcheck.aml.plugins.depep.landgovernments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.plugins.depep.landgovernments.DePepLandGovernmentsParser.Member;

class DePepLandGovernmentsParserTest {

    private static final String MOCK_KABINETT_HTML = """
            <!DOCTYPE html>
            <html>
            <body>
              <div class="teaser person-card">
                <h3>Winfried Kretschmann</h3>
                <p class="role">Ministerpräsident des Landes Baden-Württemberg</p>
              </div>
              <div class="teaser person-card">
                <h3>Dr. Danyal Bayaz</h3>
                <p class="role">Minister für Finanzen</p>
              </div>
            </body>
            </html>
            """;

    @Test
    void testExtractCabinetCards(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("kabinett.html");
        Files.writeString(input, MOCK_KABINETT_HTML);
        Path out = tempDir.resolve("out.ndjson");

        ParserContext ctx = new ParserContext("de_pep_land_governments", input, tempDir, out, Map.of());
        DePepLandGovernmentsParser parser = new DePepLandGovernmentsParser();
        ParserResult result = parser.parse(ctx);

        assertThat(result.message()).contains("2 membres des exécutifs");
        List<SourceRecord> records = parser.readSourceRecords(ctx);
        assertThat(records).hasSize(2);
        assertThat(records.get(0).name()).isEqualTo("Winfried Kretschmann");
        assertThat(records.get(1).name()).isEqualTo("Danyal Bayaz");
    }

    @Test
    void testMalformedUrlWithSpacesDoesNotCrash(@TempDir Path tempDir) throws Exception {
        // Ex: URL avec espaces non encodés de Schleswig-Holstein
        String malformedUrl = "https://www.schleswig holstein.de/DE/landesregierung/ministerien behoerden/I";
        Map<String, String> params = Map.of("urls", "[\"" + malformedUrl + "\"]");

        ParserContext ctx = new ParserContext("de_pep_land_governments", null, tempDir, tempDir.resolve("out.ndjson"), params);
        ParserResult result = new DePepLandGovernmentsParser().parse(ctx);

        assertNotNull(result);
        assertTrue(result.message().contains("ALERT SOURCE_BROKEN"));
        assertTrue(result.message().contains("schleswig holstein.de"));
    }

    @Test
    void testSinglePersonPageExtractsDetails() {
        String detailHtml = """
            <!DOCTYPE html>
            <html>
            <body>
              <h1>Dr. Markus Söder</h1>
              <div class="vita">
                <h2>Lebenslauf</h2>
                <p>Seit 16. März 2018 Bayerischer Ministerpräsident. Geboren am 5. Januar 1967 in Nürnberg.</p>
              </div>
            </body>
            </html>
            """;

        List<Member> members = DePepLandGovernmentsParser.extractFromHtml("https://www.bayern.de/ministerpraesident", "Bayern", detailHtml);
        assertThat(members).hasSize(1);
        Member soeder = members.get(0);
        assertEquals("Markus Söder", soeder.cleanName());
        assertEquals("Dr.", soeder.title());
        assertEquals("2018-03-16", soeder.startDate());
        assertNotNull(soeder.cvSummary());
        assertTrue(soeder.cvSummary().contains("Bayerischer Ministerpräsident"));
    }
}

