package com.swiftcheck.aml.plugins.depep.federalgovernment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.plugins.depep.federalgovernment.DePepFederalGovernmentParser.Member;

class DePepFederalGovernmentParserTest {

    @Test
    void testExtractCabinetMembers(@TempDir Path tempDir) throws Exception {
        String html;
        try (InputStream in = getClass().getResourceAsStream("/test_kabinett.html")) {
            assertNotNull(in);
            html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        List<Member> members = DePepFederalGovernmentParser.extractMembers(html);
        assertEquals(2, members.size());

        Member merz = members.get(0);
        assertEquals("Friedrich Merz", merz.name());
        assertEquals("Bundeskanzler", merz.role());
        assertTrue(DePepFederalGovernmentParser.isChancellor(merz.role()));

        Member klingbeil = members.get(1);
        assertEquals("Lars Klingbeil", klingbeil.name());
        assertEquals("Bundesminister der Finanzen", klingbeil.role());
        assertTrue(!DePepFederalGovernmentParser.isChancellor(klingbeil.role()));

        Path htmlFile = tempDir.resolve("kabinett.html");
        Files.writeString(htmlFile, html, StandardCharsets.UTF_8);

        ParserContext ctx = new ParserContext("de_pep_federal_government", htmlFile, tempDir, tempDir.resolve("out.ndjson"), Map.of());
        new DePepFederalGovernmentParser().parse(ctx);

        List<SourceRecord> records = new DePepFederalGovernmentParser().readSourceRecords(ctx);
        assertEquals(2, records.size());
    }

    @Test
    void testParseDetailPageExtractsCvAndStartDate() {
        String detailHtml = """
            <h1 class="bpa-title">Friedrich Merz</h1>
            <ul class="bpa-vita-list">
              <li class="bpa-vita-item"><p>Geboren am 11. November 1955 in Brilon</p></li>
              <li class="bpa-vita-item"><p><strong>Seit 1986</strong><br/>Rechtsanwalt</p></li>
              <li class="bpa-vita-item"><p><strong>Seit dem 6. Mai 2025</strong><br/>Bundeskanzler der Bundesrepublik Deutschland</p></li>
            </ul>
            """;

        Member detail = DePepFederalGovernmentParser.parseDetailPage(
                "https://www.bundesregierung.de/breg-de/bundesregierung/bundeskabinett/friedrich-merz-2342660#jumpto-2342648",
                detailHtml);

        assertNotNull(detail);
        assertEquals("friedrich-merz-2342660", detail.slug());
        assertEquals("Friedrich Merz", detail.name());
        assertEquals("2025-05-06", detail.startDate());
        assertNotNull(detail.cvSummary());
        assertTrue(detail.cvSummary().contains("Bundeskanzler der Bundesrepublik Deutschland"));
        assertTrue(detail.cvSummary().contains("Rechtsanwalt"));
    }

    @Test
    void testBrokenSourceHandlingDoesNotCrash(@TempDir Path tempDir) throws Exception {
        // Simule une URL retournant 404
        String brokenUrl = "https://www.bundesregierung.de/breg-de/bundesregierung/bundeskabinett/page-introuvable-404";
        Map<String, String> params = Map.of("urls", "[\"" + brokenUrl + "\"]");

        ParserContext ctx = new ParserContext("de_pep_federal_government", null, tempDir, tempDir.resolve("out.ndjson"), params);
        ParserResult result = new DePepFederalGovernmentParser().parse(ctx);

        assertNotNull(result);
        assertTrue(result.message().contains("ALERT SOURCE_BROKEN"));
        assertTrue(result.message().contains("page-introuvable-404"));
    }
}
