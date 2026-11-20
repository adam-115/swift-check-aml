package com.swiftcheck.aml.plugins.depep.bundesbank;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.plugins.depep.bundesbank.DePepBundesbankParser.BeiratMember;
import com.swiftcheck.aml.plugins.depep.bundesbank.DePepBundesbankParser.Group;
import com.swiftcheck.aml.plugins.depep.bundesbank.DePepBundesbankParser.HubEntry;
import com.swiftcheck.aml.plugins.depep.bundesbank.DePepBundesbankParser.Profile;

import static org.assertj.core.api.Assertions.assertThat;

class DePepBundesbankParserTest {

    /** Extrait du hub /organisation/vorstand (structure du site au 19/11/2026). */
    private static final String VORSTAND_HUB = """
            <html><body>
            <nav><a href="/de/bundesbank/organisation/vorstand/joachim-nagel-607878">Prof. Dr. Joachim Nagel</a>
              <a href="/de/bundesbank/hauptverwaltungen-filialen/bw/praesidentin">Präsidentin</a>
              <a href="/de/bundesbank/hauptverwaltungen-filialen/bw/beirat">Beirat</a>
              <a href="/de/bundesbank/hauptverwaltungen-filialen/h/praesident">Präsident</a></nav>
            <main class="main">
              <a href="/de/bundesbank/organisation/vorstand/joachim-nagel-607878" target="_self" class="teasable__open">
                <span class="copyright">© Oliver Rüther</span><h3>Prof. Dr. Joachim Nagel</h3>
                <p>Präsident der Deutschen Bundesbank,</p><p>Mitglied des EZB-Rats</p></a>
              <a href="/de/bundesbank/organisation/vorstand/sabine-mauderer-758274" class="teasable__open">
                <h3>Dr. Sabine Mauderer</h3><p>Vizepräsidentin der Deutschen Bundesbank</p></a>
              <a href="/de/bundesbank/organisation/vorstand/lutz-lienenkaemper-715586" class="teasable__open">
                <h3>Lutz Lienenkämper</h3></a>
              <a href="/de/bundesbank/organisation/vorstand/dr-philipp-nimmermann-758294" class="teasable__open">
                <h3>Dr. Philipp Nimmermann</h3></a>
            </main></body></html>
            """;

    private static final String COO_HUB = """
            <main class="main">
              <a href="/de/bundesbank/organisation/coo-und-bereiche/dr-stephan-bredt-921174" class="collection__link"
                 title="Dr. Stephan Bredt"><span><strong>Dr. Stephan Bredt</strong><span>Chief Operating Officer (COO)</span></span></a>
              <a href="/de/bundesbank/organisation/coo-und-bereiche/corina-paetsch--938924" class="collection__link"
                 title="Corina Paetsch "><strong>Corina Paetsch </strong><span>Leiterin des Zentralbereichs Baumanagement</span></a>
            </main>
            """;

    private static final String HV_PRESIDENT = """
            <html><head><meta name="description" content="Reinhold Vollbracht ist Präsident der Hauptverwaltung in Bayern."></head>
            <body><h1 class="sr-only"> Service Navigation </h1>
            <main class="main"> <h1 class="main__headline mb-4"> Reinhold Vollbracht <small class="d-block">Präsident der Hauptverwaltung in Bayern</small> </h1>
            <div class="richtext"><h2>Berufliche Tätigkeit</h2><p><strong>seit August 2022</strong>Präsident der Hauptverwaltung in Bayern</p>
            <p>Geboren 1967 in Korbach</p></div></main></body></html>
            """;

    private static final String BEIRAT = """
            <main class="main"> <h1 class="main__headline mb-4"> Beirat der Hauptverwaltung in Hes&shy;sen </h1>
            <div class="richtext"><p>Der Beirat besteht aus Vertretern verschiedener Wirtschaftszweige.</p>
            <h2>Vorsitzender</h2><ul class="rte--list"><li>Dr. Ulf Slopek<br/>Prä&shy;si&shy;den&shy;t der Haupt&shy;ver&shy;wal&shy;tung in Hessen<br/>Deutsche Bundesbank </li></ul>
            <h2>Vertreter aus dem Kreditgewerbe</h2><ul class="rte--list">
            <li><span>Michael Hoeck</span><br/><span>Vorsitzender des Vorstands </span><br/><span>Genoverband e.V.</span></li>
            <li><p id="par-a469ui">Prof. Dr. Thomas A. Lange<br/>Vorsitzender des Vorstandes National-Bank <span class="text-nowrap">AG,</span> Essen</p></li>
            <li>Mario Mattera<br/>Mitglied des Vorstands<br/>B. Metzler seel. Sohn &amp; Co. AG</li>
            <li>Ein Hinweis ohne Zeilenumbruch</li></ul></div></main>
            """;

    @Test
    void hubGivesNamesAndOriginalRoles() {
        List<HubEntry> entries = DePepBundesbankParser.extractHubEntries(DePepBundesbankParser.mainContent(VORSTAND_HUB));
        assertThat(entries).extracting(HubEntry::fullName)
                .containsExactly("Prof. Dr. Joachim Nagel", "Dr. Sabine Mauderer", "Lutz Lienenkämper", "Dr. Philipp Nimmermann");
        assertThat(entries.get(0).role()).isEqualTo("Präsident der Deutschen Bundesbank, Mitglied des EZB-Rats");
        assertThat(entries.get(0).group()).isEqualTo(Group.VORSTAND);

        List<HubEntry> coo = DePepBundesbankParser.extractHubEntries(COO_HUB);
        assertThat(coo).extracting(HubEntry::fullName).containsExactly("Dr. Stephan Bredt", "Corina Paetsch");
        assertThat(coo.get(0).role()).isEqualTo("Chief Operating Officer (COO)");
        assertThat(coo).allMatch(e -> e.group() == Group.BEREICH);
    }

    @Test
    void profileReadsHeadlineSmallRoleDateAndBirth() {
        Profile p = DePepBundesbankParser.parseProfile(HV_PRESIDENT);
        assertThat(p).isNotNull();
        assertThat(p.fullName()).isEqualTo("Reinhold Vollbracht");
        assertThat(p.role()).isEqualTo("Präsident der Hauptverwaltung in Bayern");
        assertThat(p.startDate()).isEqualTo("2022-08-01");
        assertThat(p.birthYear()).isEqualTo("1967");
        assertThat(p.birthPlace()).isEqualTo("Korbach");
    }

    @Test
    void beiratKeepsNamesTitlesAndFunctions() {
        List<BeiratMember> list = DePepBundesbankParser.parseBeirat(BEIRAT);
        assertThat(list).extracting(BeiratMember::fullName)
                .containsExactly("Dr. Ulf Slopek", "Michael Hoeck", "Prof. Dr. Thomas A. Lange", "Mario Mattera");
        assertThat(list.get(1).detail()).isEqualTo("Vorsitzender des Vorstands, Genoverband e.V.");
        assertThat(list.get(2).detail()).isEqualTo("Vorsitzender des Vorstandes National-Bank AG, Essen");
        assertThat(list.get(3).detail()).contains("B. Metzler seel. Sohn & Co. AG");
        assertThat(list.get(1).sector()).isEqualTo("Vertreter aus dem Kreditgewerbe");
        assertThat(DePepBundesbankParser.beiratBody(BEIRAT)).isEqualTo("Mitglied des Beirats der Hauptverwaltung in Hessen");
    }

    @Test
    void titlesNeverPolluteNames() {
        assertThat(DePepBundesbankParser.splitTitle("Prof. Dr. Fritzi Köhler-Geib")).containsExactly("Prof. Dr.", "Fritzi Köhler-Geib");
        assertThat(DePepBundesbankParser.splitTitle("Lutz Lienenkämper")).containsExactly(null, "Lutz Lienenkämper");
        assertThat(DePepBundesbankParser.splitFirstLast("Thomas A. Lange")).containsExactly("Thomas A.", "Lange");
        assertThat(DePepBundesbankParser.splitFirstLast("Ursula von der Leyen")).containsExactly("Ursula", "von der Leyen");
    }

    @Test
    void inputFileProducesVorstandWithMarkers(@TempDir Path tempDir) throws Exception {
        Path inputFile = tempDir.resolve("vorstand.html");
        Files.writeString(inputFile, VORSTAND_HUB);
        Path outputFile = tempDir.resolve("output.ndjson");
        ParserContext ctx = new ParserContext("de_pep_bundesbank", inputFile, tempDir, outputFile, Map.of());
        DePepBundesbankParser parser = new DePepBundesbankParser();

        ParserResult result = parser.parse(ctx);
        assertThat(result.message()).contains("4 personnes").contains("Vorstand 4");

        List<String> lines = Files.readAllLines(outputFile);
        assertThat(lines).anyMatch(l -> l.contains("\"name\":[\"Joachim Nagel\"]") && l.contains("\"title\":[\"Prof. Dr.\"]")
                && l.contains("\"firstName\":[\"Joachim\"]") && l.contains("\"lastName\":[\"Nagel\"]")
                && l.contains("pep_class: D1") && l.contains("level: FEDERAL") && l.contains("sha256: ")
                && !l.contains("pep_candidate"));
        assertThat(lines).anyMatch(l -> l.contains("\"schema\":\"Occupancy\"")
                && l.contains("Präsident der Deutschen Bundesbank, Mitglied des EZB-Rats"));

        List<SourceRecord> records = parser.readSourceRecords(ctx);
        assertThat(records).hasSize(4);
        assertThat(records.get(0).name()).isEqualTo("Prof. Dr. Joachim Nagel");
    }

    @Test
    void brokenSourceDoesNotCrash(@TempDir Path tempDir) {
        Map<String, String> params = Map.of("hubs", "https://www.bundesbank.de/de/page-404-inconnue",
                "hauptverwaltungen", "false", "delayMs", "0");
        ParserContext ctx = new ParserContext("de_pep_bundesbank", null, tempDir, tempDir.resolve("out.ndjson"), params);
        DePepBundesbankParser parser = new DePepBundesbankParser();
        DePepBundesbankParser.CrawlResult crawl = parser.crawl(ctx, params);
        assertThat(crawl.members()).isEmpty();
        assertThat(crawl.brokenSources()).hasSize(1);
    }

    /** Exécution réelle sur bundesbank.de : {@code mvn test -Dlive=true -Dtest=DePepBundesbankParserTest}. */
    @Test
    @EnabledIfSystemProperty(named = "live", matches = "true")
    void liveRun(@TempDir Path tempDir) throws Exception {
        Path out = tempDir.resolve("out.ndjson");
        ParserContext ctx = new ParserContext("de_pep_bundesbank", null, tempDir, out, Map.of());
        ParserResult result = new DePepBundesbankParser().parse(ctx);
        System.out.println(result.message());
        List<String> lines = Files.readAllLines(out);
        lines.stream().filter(l -> l.contains("\"schema\":\"Person\"")).forEach(System.out::println);
        assertThat(result.message()).doesNotContain("SOURCE_BROKEN");
    }
}
