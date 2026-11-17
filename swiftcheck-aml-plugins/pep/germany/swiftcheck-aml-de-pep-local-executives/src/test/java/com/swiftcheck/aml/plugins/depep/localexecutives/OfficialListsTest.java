package com.swiftcheck.aml.plugins.depep.localexecutives;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * Fichiers officiels relevés le 16 novembre 2026 : page « Landkreise und Landräte » du Deutscher Landkreistag,
 * répertoire des communes de Destatis (30/09/2026) et liste des maires du Bayerisches Landesamt für Statistik (10/11/2026).
 */
class OfficialListsTest {

    static final String BAYERN = """
            [{"land":"BY","publisher":"Bayerisches Landesamt für Statistik",
              "url":"https://www.statistik.bayern.de/mam/wahlen/kommunalwahlen/bgm/wahlergebnisse_mandatsr%C3%A4ger.xlsx",
              "format":"xlsx","sheet":"Mandatsträger","agsPrefix":"09","nameOrder":"lastFirst","roles":"(?i)bürgermeister",
              "columns":{"key":"Schlüssel","place":"Gemeindename/Landkreis","role":"Amtsbezeichnung","name":"Amtsinhaber/in",
                         "gender":"Geschlecht","birthYear":"Geburtsjahr","party":"Wahlvorschlag","start":"Erster Amtsantritt"}}]""";

    static final String NRW = """
            {"land":"NW","publisher":"Landeswahlleiterin Nordrhein-Westfalen","format":"perCity","nameOrder":"lastFirst",
             "url":"https://www.wahlergebnisse.nrw/kommunalwahlen/2025/aktuell/txtdateien/b{ags6}kw2500.txt",
             "elected":"gewählt:\\\\s*\\\\R\\\\s*(.+?)\\\\s*\\\\(([^()]*)\\\\)\\\\s*\\\\R\\\\s*(\\\\d{2}\\\\.\\\\d{2}\\\\.\\\\d{4})",
             "roleFrom":"Wahl der ((?:Ober)?[Bb]ürgermeister)"}""";

    static byte[] bytes(String file) throws IOException {
        try (InputStream in = OfficialListsTest.class.getResourceAsStream("/official/" + file)) {
            if (in == null) {
                throw new IOException("HTTP 404");
            }
            return in.readAllBytes();
        }
    }

    @Test
    void readsTheElectedMayorOfEachNrwCity() throws IOException {
        OfficialLists.MayorSource nrw = DePepLocalExecutivesParser.mayorSources("[" + NRW + "]").get(0);

        OfficialLists.Executive koeln = OfficialLists.electedMayor(new String(bytes("nrw-b315000kw2500.txt"), StandardCharsets.UTF_8),
                "05315000", nrw, "u");
        OfficialLists.Executive ahaus = OfficialLists.electedMayor(new String(bytes("nrw-b554004kw2500.txt"), StandardCharsets.UTF_8),
                "05554004", nrw, "u");

        assertThat(koeln.fullName()).isEqualTo("Torsten Burmester");
        assertThat(koeln.role()).isEqualTo("Oberbürgermeister");
        assertThat(koeln.party()).isEqualTo("SPD");
        assertThat(koeln.start()).isEqualTo("2025-09-28");
        // « Voß, Karola (Maria) (EZB) »
        assertThat(ahaus.fullName()).isEqualTo("Karola Voß");
        assertThat(ahaus.role()).isEqualTo("Bürgermeister");
        assertThat(ahaus.party()).isEqualTo("EZB");
    }

    @Test
    void readsTheNrwCitiesOneByOneAndReportsTheMissingOnes() {
        Map<String, String> p = Map.of("landraeteUrl", "https://www.landkreistag.de/landraete", "populationUrl", "https://www.destatis.de/gv.xlsx",
                "mayorSources", "[" + NRW + "]");
        DePepLocalExecutivesParser.Reading reading = DePepLocalExecutivesParser.read(p, url -> {
            if (url.contains("landkreistag")) {
                return bytes("landkreistag-landraete.html");
            }
            if (url.contains("destatis")) {
                return bytes("destatis-gemeindeverzeichnis.xlsx");
            }
            return bytes(url.endsWith("b315000kw2500.txt") ? "nrw-b315000kw2500.txt" : "absent");
        }, null);

        assertThat(reading.executives()).filteredOn(e -> "Stadt".equals(e.type())).extracting(OfficialLists.Executive::place)
                .containsExactly("Köln");
        assertThat(reading.message()).contains("(NW 1)").contains("ALERT : maires NW non lus pour").contains("Düsseldorf (HTTP 404)");
    }

    @Test
    void readsThe294LandraeteOfTheLandkreistag() throws IOException {
        List<OfficialLists.Executive> landraete = OfficialLists.landraete(
                new String(bytes("landkreistag-landraete.html"), StandardCharsets.UTF_8), "dlt",
                Map.of("Region Hannover", "Regionspräsident"));

        assertThat(landraete).hasSize(294);
        OfficialLists.Executive ante = landraete.stream().filter(e -> e.place().equals("Breisgau-Hochschwarzwald")).findFirst().orElseThrow();
        assertThat(ante.land()).isEqualTo("BW");
        assertThat(ante.title()).isEqualTo("Dr.");
        assertThat(ante.fullName()).isEqualTo("Christian Ante");
        // « Michael Koller, MdL » : le mandat de député n'est pas gardé dans le nom
        assertThat(landraete).extracting(OfficialLists.Executive::fullName).contains("Michael Koller", "Arne von Boetticher");
        assertThat(landraete).filteredOn(e -> e.place().equals("Region Hannover")).extracting(OfficialLists.Executive::role)
                .containsExactly("Regionspräsident");
        assertThat(landraete).allMatch(e -> e.land() != null);
    }

    @Test
    void readsThePopulationOfEachMunicipality() throws IOException {
        Map<String, OfficialLists.Municipality> m = OfficialLists.municipalities(bytes("destatis-gemeindeverzeichnis.xlsx"));

        assertThat(m.get("01001000").name()).isEqualTo("Flensburg, Stadt");
        assertThat(m.get("01001000").population()).isEqualTo(95568);
        assertThat(m.get("09162000").shortName()).isEqualTo("München");
        assertThat(m.values().stream().filter(x -> x.population() >= 50_000).count()).isBetween(180L, 200L);
    }

    @Test
    void readsTheMayorsOfBavariaAndKeepsTheBigCities(@TempDir Path raw) throws IOException {
        Map<String, String> files = Map.of(
                "https://www.landkreistag.de/landraete", "landkreistag-landraete.html",
                "https://www.destatis.de/gv.xlsx", "destatis-gemeindeverzeichnis.xlsx",
                "https://www.statistik.bayern.de/mam/wahlen/kommunalwahlen/bgm/wahlergebnisse_mandatsr%C3%A4ger.xlsx", "bayern-mandatstraeger.xlsx");
        Map<String, String> p = Map.of("landraeteUrl", "https://www.landkreistag.de/landraete", "populationUrl", "https://www.destatis.de/gv.xlsx",
                "mayorSources", BAYERN, "minPopulation", "50000");

        DePepLocalExecutivesParser.Reading reading = DePepLocalExecutivesParser.read(p, url -> bytes(files.get(url)), raw);

        System.out.println("MESSAGE " + reading.message());
        List<OfficialLists.Executive> mayors = reading.executives().stream().filter(e -> "Stadt".equals(e.type())).toList();
        assertThat(mayors).hasSizeBetween(15, 30);
        OfficialLists.Executive ingolstadt = mayors.stream().filter(e -> e.place().equals("Ingolstadt")).findFirst().orElseThrow();
        assertThat(ingolstadt.fullName()).isEqualTo("Michael Kern");
        assertThat(ingolstadt.title()).isEqualTo("Dr.");
        assertThat(ingolstadt.birthYear()).isEqualTo("1975");
        assertThat(ingolstadt.party()).isEqualTo("CSU");
        assertThat(mayors).extracting(OfficialLists.Executive::place).contains("München", "Nürnberg", "Augsburg").doesNotContain("Erlbach");
        assertThat(reading.message()).contains("294 Landräte lus").contains("maires de villes d'au moins 50000 hab. lus (BY ")
                .contains("sans liste officielle de maires lue").contains("NW ");
        assertThat(raw.resolve("landraete.html")).exists();

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(DePepLocalExecutivesParser.ID_PREFIX, DePepLocalExecutivesParser.TOPIC);
        DePepLocalExecutivesParser.convertOfficial(reading.executives(), dataset);
        FtmEntity kern = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema()) && e.getValues("name").contains("Michael Kern"))
                .findFirst().orElseThrow();
        assertThat(kern.getValues("political")).containsExactly("CSU");
        assertThat(dataset.qualityReport().getTotalRejected()).isZero();
    }

    @Test
    void completesTheCitiesWithoutOfficialListFromWikidata() throws IOException {
        Map<String, OfficialLists.Municipality> all = OfficialLists.municipalities(bytes("destatis-gemeindeverzeichnis.xlsx"));
        List<String> ambiguous = new java.util.ArrayList<>();

        // réponse réelle de Wikidata du 16/11/2026 pour les 98 villes sans liste officielle lue
        Map<String, OfficialLists.Executive> mayors = WikidataMayors.parse(new String(bytes("wikidata-maires.json"), StandardCharsets.UTF_8),
                all, ambiguous);

        assertThat(mayors).hasSizeBetween(80, 98);
        assertThat(ambiguous).contains("Saarbrücken");
        OfficialLists.Executive any = mayors.values().iterator().next();
        assertThat(any.note()).contains("Wikidata").contains("à confirmer");
        assertThat(any.sourceUrl()).startsWith("http://www.wikidata.org/entity/Q");
        assertThat(mayors.values()).allMatch(e -> e.lastName() != null && !e.lastName().matches("Q\\d+"));
        assertThat(WikidataMayors.queryUrl(WikidataMayors.DEFAULT_ENDPOINT, List.of("08111000"))).contains("P439").contains("08111000");
    }

    @Test
    void splitsNames() {
        assertThat(OfficialLists.splitLastFirst("Kern Dr. Michael")).containsExactly("Dr.", "Michael", "Kern");
        assertThat(OfficialLists.splitLastFirst("Kriegl, Gisela")).containsExactly(null, "Gisela", "Kriegl");
        assertThat(OfficialLists.splitLastFirst("von Dobschütz Dr. Christian")).containsExactly("Dr.", "Christian", "von Dobschütz");
        assertThat(OfficialLists.splitFirstLast("Prof. Dr. Christian Dusch")).containsExactly("Prof. Dr.", "Christian", "Dusch");
        assertThat(OfficialLists.splitFirstLast("Arne von Boetticher")).containsExactly(null, "Arne", "von Boetticher");
    }

    @Test
    void aBrokenSourceIsReportedWithoutStoppingTheOthers() {
        Map<String, String> p = Map.of("landraeteUrl", "https://www.landkreistag.de/landraete", "populationUrl", "https://www.destatis.de/gv.xlsx");
        DePepLocalExecutivesParser.Reading reading = DePepLocalExecutivesParser.read(p, url -> {
            if (url.contains("destatis")) {
                throw new IOException("HTTP 503");
            }
            return bytes("landkreistag-landraete.html");
        }, null);
        assertThat(reading.executives()).hasSize(294);
        assertThat(reading.message()).contains("ALERT SOURCE_BROKEN : population des communes non lue (HTTP 503)");
    }
}
