package com.swiftcheck.aml.plugins.depep.bundesrat;

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
import com.swiftcheck.aml.plugins.depep.bundesrat.DePepBundesratParser.Member;

class DePepBundesratParserTest {

    @Test
    void testExtractMembers(@TempDir Path tempDir) throws Exception {
        String html;
        try (InputStream in = getClass().getResourceAsStream("/test_bundesrat.html")) {
            assertNotNull(in);
            html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        List<Member> members = DePepBundesratParser.extractMembers(html);
        assertEquals(2, members.size());

        Member wuest = members.get(0);
        assertEquals("wuest-hendrik", wuest.slug());
        assertEquals("Hendrik Wüst", wuest.name());
        assertEquals("CDU", wuest.party());
        assertEquals("Nordrhein-Westfalen", wuest.state());
        assertTrue(DePepBundesratParser.isMinisterPresident(wuest.function()));

        Member crumbach = members.get(1);
        assertEquals("crumbach-robert", crumbach.slug());
        assertEquals("Robert Crumbach", crumbach.name());
        assertEquals("SPD", crumbach.party());
        assertEquals("Brandenburg", crumbach.state());
        assertTrue(!DePepBundesratParser.isMinisterPresident(crumbach.function()));

        Path htmlFile = tempDir.resolve("bundesrat.html");
        Files.writeString(htmlFile, html, StandardCharsets.UTF_8);

        ParserContext ctx = new ParserContext("de_pep_bundesrat", htmlFile, tempDir, tempDir.resolve("out.ndjson"), Map.of());
        new DePepBundesratParser().parse(ctx);

        List<SourceRecord> records = new DePepBundesratParser().readSourceRecords(ctx);
        assertEquals(2, records.size());
    }

    @Test
    void testParseDetailPageExtractsCvAndStartDate() {
        String detailHtml = """
            <div class="text-box">
              <h1>Rudolf Hoogvliet&nbsp;&#x007C;&nbsp;<span class="organization-name">BÜNDNIS 90/DIE GRÜNEN</span></h1>
              <h2>Staatssekretär für Medienpolitik und Bevollmächtigter des Landes Baden-Württemberg beim Bund</h2>
            </div>
            <div id="person" class="module type-2 member member-furtherdetails">
              <div class="module-box">
                <h1>Zur Person</h1>
                <div class="row">
                  <div class="text-box">
                    <p>Geboren am 18.08.1958 in Vlissingen/Niederlande.</p>
                    <p>Seit 12.05.2021 Staatssekretär für Medienpolitik und Bevollmächtigter des Landes Baden-Württemberg beim Bund.</p>
                    <p>Mitglied des Bundesrates seit 12.05.2021.</p>
                  </div>
                </div>
              </div>
            </div>
            """;

        Member m = DePepBundesratParser.parseDetailPage(
                "https://www.bundesrat.de/SharedDocs/personen/DE/laender/bw/hoogvliet-rudolf.html",
                detailHtml);

        assertNotNull(m);
        assertEquals("hoogvliet-rudolf", m.slug());
        assertEquals("Rudolf Hoogvliet", m.name());
        assertEquals("BÜNDNIS 90/DIE GRÜNEN", m.party());
        assertEquals("bw", m.landCode());
        assertEquals("2021-05-12", m.startDate());
        assertNotNull(m.cvSummary());
        assertTrue(m.cvSummary().contains("Vlissingen"));
        assertTrue(m.cvSummary().contains("Staatssekretär für Medienpolitik"));
    }

    @Test
    void testParseDeparturesAndCoolingOff() {
        String aenderungenHtml = """
            <h1>Neue und ausgeschiedene Mitglieder des Bundesrates</h1>
            <p>Staatsminister a. D. <strong>Rainer Robra</strong> ist mit Ablauf des 5. Oktober 2026 aus der Landesregierung Sachsen-Anhalt ausgeschieden. Zugleich endeten seine Mitgliedschaften im Bundesrat.</p>
            <p>Am 30. September 2026 sind Staatsminister a. D. <strong>Georg Eisenreich</strong> und Staatsminister a. D. <strong>Eric Beißwenger</strong> aus der Bayerischen Staatsregierung und damit aus dem Bundesrat ausgeschieden.</p>
            """;

        Map<String, String> departures = DePepBundesratParser.parseDepartures(aenderungenHtml);
        assertEquals(3, departures.size());
        assertEquals("2026-10-05", departures.get("Rainer Robra"));
        assertEquals("2026-09-30", departures.get("Georg Eisenreich"));
        assertEquals("2026-09-30", departures.get("Eric Beißwenger"));
    }

    @Test
    void testBrokenSourceAlertDoesNotCrash(@TempDir Path tempDir) throws Exception {
        String brokenUrl = "https://www.bundesrat.de/SharedDocs/personen/DE/laender/bw/page-inexistante-404.html";
        Map<String, String> params = Map.of("urls", "[\"" + brokenUrl + "\"]");

        ParserContext ctx = new ParserContext("de_pep_bundesrat", null, tempDir, tempDir.resolve("out.ndjson"), params);
        ParserResult result = new DePepBundesratParser().parse(ctx);

        assertNotNull(result);
        assertTrue(result.message().contains("ALERT SOURCE_BROKEN"));
        assertTrue(result.message().contains("page-inexistante-404"));
    }
}
