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

    /** Extrait de praesidium/aktuell/aktuell.html (structure du site au 19/11/2026). */
    private static final String PRAESIDIUM = """
            <h2>Bundesratspräsident</h2> <ul class="members-list"> <li class=""> <div class="row vcard">
            <div class="members-details"> <a href="https://www.bundesrat.de/SharedDocs/personen/DE/laender/nw/wuest-hendrik.html"><img src="https://www.bundesrat.de/x/wuest.jpg" title="Foto: Ministerpräsident Hendrik Wüst © Land NRW" class="photo"/></a>
            <h2> <a href="https://www.bundesrat.de/SharedDocs/personen/DE/laender/nw/wuest-hendrik.html">Hendrik Wüst&nbsp;&#x007C;&nbsp;<span class="organization-name">CDU</span> </a> </h2> <p class="state">Nordrhein-Westfalen</p></div>
            <div class="members-text"> <h3>Ministerpräsident des Landes Nordrhein-Westfalen</h3> <h3>Präsident des Bundesrates</h3></div> </div> </li> </ul>
            <h2>1. Vizepräsident</h2> <ul class="members-list"> <li class=""> <div class="row vcard">
            <div class="members-details"> <h2> <a href="https://www.bundesrat.de/SharedDocs/personen/DE/laender/hb/bovenschulte-andreas.html">Dr. Andreas Bovenschulte&nbsp;&#x007C;&nbsp;<span class="organization-name">SPD</span> </a> </h2> <p class="state">Freie Hansestadt Bremen</p></div>
            <div class="members-text"> <h3>Präsident des Senats der Freien Hansestadt Bremen</h3> <h3>Bürgermeister</h3> <h3>Erster Vizepräsident des Bundesrates</h3></div> </div> </li> </ul>
            """;

    /** Extrait de staendiger-beirat.html. */
    private static final String BEIRAT = """
            <div class="rack-teaser"> <h2> </h2> <a href="https://www.bundesrat.de/SharedDocs/personen/DE/laender/by/gernbauer-karolina.html">
            <span class="picture-element"><img src="x.jpg" title="Foto: Staatsrätin Karolina Gernbauer"/></span> <h3>Karolina  Gernbauer</h3> <p class="bundesland">Freistaat Bayern</p> </a> </div>
            <div class="rack-teaser"> <h2> </h2> <a href="https://www.bundesrat.de/SharedDocs/personen/DE/laender/nw/ro%C3%9Fbach-matthias.html">
            <h3>Dr. Matthias Roßbach</h3> <p class="bundesland">Nordrhein-Westfalen</p> </a> </div>
            """;

    @Test
    void praesidiumKeepsAllFunctionsAndSeparatesTitles() {
        List<Member> list = DePepBundesratParser.extractMembers(PRAESIDIUM);
        assertEquals(2, list.size());
        assertEquals(List.of("Ministerpräsident des Landes Nordrhein-Westfalen", "Präsident des Bundesrates"),
                list.get(0).functions());
        Member bovenschulte = list.get(1);
        assertEquals("Andreas Bovenschulte", bovenschulte.name());
        assertEquals("Dr.", bovenschulte.title());
        assertTrue(DePepBundesratParser.isMinisterPresident(bovenschulte.functions()));
    }

    @Test
    void beiratMembersAreNotBundesratMembers() {
        List<Member> list = DePepBundesratParser.extractBeirat(BEIRAT, "https://www.bundesrat.de/DE/bundesrat/staendiger-beirat/staendiger-beirat.html");
        assertEquals(2, list.size());
        assertEquals("Karolina Gernbauer", list.get(0).name());
        assertEquals("by", list.get(0).landCode());
        assertEquals("Freistaat Bayern", list.get(0).state());
        assertTrue(list.get(0).roles().contains(DePepBundesratParser.Role.BEIRAT));
        assertTrue(!list.get(0).roles().contains(DePepBundesratParser.Role.MEMBER));
        assertEquals("Matthias Roßbach", list.get(1).name());
        assertEquals("Dr.", list.get(1).title());
    }

    @Test
    void detailHeaderWithoutPartyAndSeveralFunctions() {
        String html = """
                <nav><h2>Suche</h2></nav>
                <div class="text-box"> <h1>Karolina Gernbauer</h1> <h2>Staatsrätin</h2> <h2>Bevollmächtigte des Freistaates Bayern beim Bund</h2> </div>
                """;
        Member m = DePepBundesratParser.parseDetailPage(
                "https://www.bundesrat.de/SharedDocs/personen/DE/laender/by/gernbauer-karolina.html", html);
        assertNotNull(m);
        assertEquals("Karolina Gernbauer", m.name());
        assertEquals(List.of("Staatsrätin", "Bevollmächtigte des Freistaates Bayern beim Bund"), m.functions());
        assertEquals(null, m.party());
    }

    @Test
    void titlesAndRetiredMentionNeverStayInNames() {
        assertEquals("Prof. Dr.", DePepBundesratParser.splitTitle("Prof. Dr. Kristina Sinemus")[0]);
        assertEquals("Kristina Sinemus", DePepBundesratParser.splitTitle("Prof. Dr. Kristina Sinemus")[1]);
        assertEquals("Rainer Robra", DePepBundesratParser.splitTitle("Rainer Robra a. D.")[1]);
        assertEquals("Karolina Gernbauer", DePepBundesratParser.nameFromSlug("gernbauer-karolina"));
    }

    @Test
    void outputUsesGermanLabelsAndMarkers(@TempDir Path tempDir) throws Exception {
        Path htmlFile = tempDir.resolve("praesidium.html");
        Files.writeString(htmlFile, PRAESIDIUM, StandardCharsets.UTF_8);
        Path out = tempDir.resolve("out.ndjson");
        ParserContext ctx = new ParserContext("de_pep_bundesrat", htmlFile, tempDir, out, Map.of());
        new DePepBundesratParser().parse(ctx);
        List<String> lines = Files.readAllLines(out);
        // Le fichier d'entrée est lu comme une liste de membres : les fonctions décident du Präsidium.
        assertTrue(lines.stream().noneMatch(l -> l.contains("Membre du Bundesrat")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("\"name\":[\"Andreas Bovenschulte\"]")
                && l.contains("\"title\":[\"Dr.\"]") && l.contains("level: FEDERAL") && l.contains("level: LAENDER")
                && l.contains("pep_class: B2 (Mitglied des Bundesrates)") && l.contains("sha256: ")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("\"schema\":\"Position\"") && l.contains("Präsident des Bundesrates")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("pep_class: A1 (Präsident des Bundesrates)")));
    }

    /** Exécution réelle sur bundesrat.de : {@code mvn test -Dlive=true -Dtest=DePepBundesratParserTest#liveRun}. */
    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "live", matches = "true")
    void liveRun(@TempDir Path tempDir) throws Exception {
        String base = "https://www.bundesrat.de/DE/bundesrat/";
        StringBuilder urls = new StringBuilder("[\"" + base + "mitglieder/mitglieder-node.html?param3=Alle&param2=member&param1=state-1\","
                + "\"" + base + "mitglieder/aenderungen/aenderungen-node.html\","
                + "\"" + base + "praesidium/praesidium-node.html\","
                + "\"" + base + "staendiger-beirat/staendiger-beirat.html\"");
        for (String land : List.of("bw", "by", "be", "bb", "hb", "hh", "he", "mv", "ni", "nw", "rp", "sl", "sn", "st", "sh", "th")) {
            urls.append(",\"").append(base).append("laender/").append(land).append('/').append(land).append("-node.html\"");
        }
        urls.append(']');
        Path out = tempDir.resolve("out.ndjson");
        ParserContext ctx = new ParserContext("de_pep_bundesrat", null, tempDir, out, Map.of("urls", urls.toString()));
        ParserResult result = new DePepBundesratParser().parse(ctx);
        System.out.println("RESULT " + result.message());
        Files.readAllLines(out).stream().filter(l -> l.contains("\"schema\":\"Person\"")).forEach(l -> System.out.println("PERSON " + l));
        // bundesrat.de est parfois lent : une page en échec est tolérée (AC4), pas une liste incomplète.
        assertTrue(result.message().matches("(?s)^1[5-9]\\d membres du Bundesrat.*dont 16 chefs de gouvernement.*Ständiger Beirat : 16\\..*"),
                result.message());
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
