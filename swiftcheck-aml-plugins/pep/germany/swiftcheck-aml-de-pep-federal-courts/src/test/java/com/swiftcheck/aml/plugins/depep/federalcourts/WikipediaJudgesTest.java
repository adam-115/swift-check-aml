package com.swiftcheck.aml.plugins.depep.federalcourts;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/** Listes Wikipédia DE et plans de répartition officiels relevés le 15 novembre 2026. */
class WikipediaJudgesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 15);

    private static String resource(String file) throws IOException {
        try (InputStream in = WikipediaJudgesTest.class.getResourceAsStream("/" + file)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Réponse de l'API MediaWiki autour d'un wikitext. */
    private static String apiResponse(String wikitext) throws IOException {
        return new ObjectMapper().writeValueAsString(java.util.Map.of("parse",
                java.util.Map.of("title", "x", "wikitext", java.util.Map.of("*", wikitext))));
    }

    private static List<WikipediaJudges.Judge> current(String court) throws IOException {
        return WikipediaJudges.parse(resource("wikipedia/" + court + ".wikitext")).stream()
                .filter(j -> j.keep(TODAY, 0)).toList();
    }

    @Test
    void readsNamesBirthYearsDatesAndRolesOfARow() throws Exception {
        List<WikipediaJudges.Judge> all = WikipediaJudges.parse(resource("wikipedia/bgh.wikitext"));

        WikipediaJudges.Judge adams = all.stream().filter(j -> j.lastName().equals("Adams")).findFirst().orElseThrow();
        assertThat(adams.fullName()).isEqualTo("Elke Adams");
        assertThat(adams.birthYear()).isEqualTo(1975);
        assertThat(adams.start()).isEqualTo(LocalDate.of(2022, 6, 1));
        assertThat(adams.end()).isNull();
        assertThat(adams.role("Bundesgerichtshof")).isEqualTo("Richter am Bundesgerichtshof");

        // date de début suivie d'une référence <ref> qui contient d'autres dates
        WikipediaJudges.Judge allgayer = all.stream().filter(j -> j.fullName().equals("Angelika Allgayer")).findFirst().orElseThrow();
        assertThat(allgayer.start()).isEqualTo(LocalDate.of(2021, 2, 1));
        assertThat(allgayer.end()).isNull();

        // présidente depuis le 01/09/2026 (dernière colonne)
        WikipediaJudges.Judge angerer = all.stream().filter(j -> j.lastName().equals("Angerer")).findFirst().orElseThrow();
        assertThat(angerer.role("Bundesgerichtshof")).isEqualTo("Präsident des Bundesgerichtshofs");

        // décédé : jamais publié
        WikipediaJudges.Judge ahlt = all.stream().filter(j -> j.lastName().equals("Ahlt")).findFirst().orElseThrow();
        assertThat(ahlt.deceased()).isTrue();
        assertThat(ahlt.keep(TODAY, 1200)).isFalse();
    }

    @Test
    void findsTheSittingJudgesOfTheFiveFederalCourts() throws Exception {
        // ordres de grandeur des effectifs (2026) : BGH ≈ 160, BVerwG ≈ 55, BFH ≈ 60, BAG ≈ 40, BSG ≈ 45
        assertThat(current("bgh")).hasSizeBetween(140, 180);
        assertThat(current("bverwg")).hasSizeBetween(45, 65);
        assertThat(current("bfh")).hasSizeBetween(45, 75);
        assertThat(current("bag")).hasSizeBetween(30, 50);
        assertThat(current("bsg")).hasSizeBetween(35, 55);
    }

    @Test
    void mostSittingJudgesAreConfirmedByTheOfficialPlans() throws Exception {
        String bgh = resource("plans/bgh.txt");
        String bverwg = resource("plans/bverwg.txt");

        long bghConfirmed = current("bgh").stream().filter(j -> WikipediaJudges.inOfficialPlan(j.lastName(), bgh)).count();
        long bverwgConfirmed = current("bverwg").stream().filter(j -> WikipediaJudges.inOfficialPlan(j.lastName(), bverwg)).count();

        assertThat((double) bghConfirmed / current("bgh").size()).isGreaterThan(0.8);
        assertThat((double) bverwgConfirmed / current("bverwg").size()).isGreaterThan(0.8);
        // mot entier seulement : « Ott » n'est pas trouvé dans « Ottmar »
        assertThat(WikipediaJudges.inOfficialPlan("Ott", "Vorsitzender Richter Ottmar")).isFalse();
        assertThat(WikipediaJudges.inOfficialPlan("von Albert", "Richter Albert, Richterin Meier")).isTrue();
    }

    @Test
    void discoversTheLatestBghPlanPdf() throws Exception {
        String base = "https://www.bundesgerichtshof.de/DE/DasGericht/Geschaeftsverteilung/geschaeftsverteilung_node.html";

        String yearPage = OfficialPlans.latestBghYearPage(resource("plans/bgh-geschaeftsverteilung.html"), base);
        String pdf = OfficialPlans.pdfLink(resource("plans/bgh-plan-2026.html"), yearPage);

        assertThat(yearPage).contains("/Geschaeftsverteilungsplan2026/");
        assertThat(pdf).startsWith("https://www.bundesgerichtshof.de/SharedDocs/Downloads/")
                .contains("geschaeftsverteilung2026.pdf?__blob=publicationFile").doesNotContain("&amp;");
    }

    @Test
    void publishesTheJudgesWithoutRepeatingThePresidentReadOnTheOfficialPage(@TempDir Path raw) throws Exception {
        var list = new DePepFederalCourtsParser.JudgeListConfig("bverwg", "Bundesverwaltungsgericht", "BVerwG",
                "Liste_der_Richter_am_deutschen_Bundesverwaltungsgericht",
                "https://www.bverwg.de/gvp_bverwg.pdf", null, 0L);
        String wikitext = resource("wikipedia/bverwg.wikitext");
        String plan = resource("plans/bverwg.txt");
        List<DePepFederalCourtsParser.JudgeOutcome> lists = DePepFederalCourtsParser.readJudgeLists(List.of(list),
                url -> apiResponse(wikitext), l -> plan, raw);
        WikipediaJudges.Judge someone = current("bverwg").get(0);
        var court = new DePepFederalCourtsParser.CourtConfig("bverwg", "Bundesverwaltungsgericht", "BVerwG", "https://www.bverwg.de/");
        var official = List.of(new DePepFederalCourtsParser.Outcome(court, List.of(new FederalPages.Member(someone.fullName(),
                null, someone.fullName(), "Präsident", null, null)), null));
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(DePepFederalCourtsParser.ID_PREFIX, DePepFederalCourtsParser.TOPIC);

        String message = DePepFederalCourtsParser.convertJudges(lists, official, TODAY, 12, dataset);

        List<FtmEntity> persons = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).toList();
        assertThat(persons).noneMatch(p -> p.getValues("name").contains(someone.fullName()));
        assertThat(persons.size()).isGreaterThanOrEqualTo(current("bverwg").size() - 1);
        assertThat(persons).allMatch(p -> p.getValues("country").contains("de"));
        assertThat(persons).anyMatch(p -> p.getValues("notes").stream().anyMatch(n -> n.contains("présent dans le plan")));
        assertThat(message).startsWith(persons.size() + " juges lus sur les listes Wikipédia : BVerwG " + persons.size())
                .contains("confirmés par le plan officiel");
        assertThat(raw.resolve("wikipedia-bverwg.json")).exists();
        assertThat(raw.resolve("plan-bverwg.txt")).exists();
    }

    @Test
    void keepsTheListWhenThePlanCannotBeRead(@TempDir Path raw) throws Exception {
        var list = new DePepFederalCourtsParser.JudgeListConfig("bverwg", "Bundesverwaltungsgericht", "BVerwG",
                "Liste", "https://www.bverwg.de/gvp_bverwg.pdf", null, 0L);
        String wikitext = resource("wikipedia/bverwg.wikitext");

        var lists = DePepFederalCourtsParser.readJudgeLists(List.of(list), url -> apiResponse(wikitext),
                l -> { throw new IOException("PDF illisible"); }, raw);

        assertThat(lists.get(0).judges()).isNotEmpty();
        assertThat(lists.get(0).planText()).isNull();
        assertThat(lists.get(0).problem()).contains("plan officiel non lu");
    }
}
