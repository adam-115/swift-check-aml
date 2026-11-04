package com.swiftcheck.aml.plugins.depep.federalcourts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

/** Pages des présidences et liste des juges du BVerfG téléchargées le 4 novembre 2026 (dossier test/resources/courts). */
class FederalPagesTest {

    private static String page(String key) throws IOException {
        try (InputStream in = FederalPagesTest.class.getResourceAsStream("/courts/" + key + ".html")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<String> read(String key) throws IOException {
        return FederalPages.EXTRACTORS.get(key).apply(PageText.lines(page(key))).stream()
                .map(m -> m.role() + "|" + m.cleanName() + "|" + (m.start() == null ? "" : m.start()) + "|" + (m.end() == null ? "" : m.end())).toList();
    }

    @Test
    void bverfgHasSixteenJudgesInTwoSenates() throws IOException {
        List<String> judges = read("bverfg");
        assertThat(judges).hasSize(16);
        assertThat(judges.stream().map(j -> j.split("\\|")[1]).toList()).containsExactly(
                "Stephan Harbarth", "Yvonne Ott", "Henning Radtke", "Ines Härtel", "Heinrich Amadeus Wolff", "Martin Eifert",
                "Miriam Meßling", "Günter Spinner", "Ann-Katrin Kaufhold", "Christine Langenfeld", "Astrid Wallrabenstein",
                "Rhona Fetzer", "Thomas Offenloch", "Peter Frank", "Holger Wöckel", "Sigrid Emmenegger");
        assertThat(judges.get(0)).contains("Erster Senat");
        assertThat(judges.get(8)).contains("Zweiter Senat");
    }

    @Test
    void bghCurrentAndFormerPresidentsWithTheirDates() throws IOException {
        List<String> presidents = read("bgh");
        assertThat(presidents.get(0)).isEqualTo("Präsident(in) des Bundesgerichtshofs|Karin Angerer|2026-09-01|");
        assertThat(presidents.get(1)).isEqualTo("Ehemalige(r) Präsident(in) des Bundesgerichtshofs|Bettina Limperg|2014-07-01|2026-08-31");
        assertThat(presidents).hasSize(10);
    }

    @Test
    void bverwgHasAViceChairAndNoPresident() throws IOException {
        assertThat(read("bverwg")).containsExactly("Vizepräsidentin|Susanne Rublack||");
    }

    @Test
    void bagPresidentAndVicePresident() throws IOException {
        assertThat(read("bag")).containsExactly("Präsident(in) des Bundesarbeitsgerichts|Inken Gallner||",
                "Vizepräsident(in) des Bundesarbeitsgerichts|Heinrich Kiel||");
    }

    @Test
    void bsgPresidentAndVicePresident() throws IOException {
        assertThat(read("bsg")).containsExactly("Präsidentin des Bundessozialgerichts|Christine Fuchsloch||",
                "Vizepräsident des Bundessozialgerichts|Andreas Heinz||");
    }

    @Test
    void bfhPresidentAndVicePresidentWithDates() throws IOException {
        assertThat(read("bfh")).containsExactly("Präsident|Hans-Josef Thesling|2022-01-25|", "Vizepräsident|Meinhard Wittwer|2022-11-21|");
    }

    // ------------------------------------------------------------------ plugin complet

    private static DePepFederalCourtsParser.Fetcher fixtures() throws IOException {
        var config = DePepFederalCourtsParser.loadConfig();
        return url -> {
            for (var c : config.courts()) {
                if (c.url().equals(url)) {
                    return page(c.key());
                }
            }
            throw new IOException("URL inattendue");
        };
    }

    @Test
    void writesTheDatasetAndDropsFormerPresidentsOlderThanTwelveMonths(@TempDir Path raw) throws Exception {
        var outcomes = DePepFederalCourtsParser.readAll(DePepFederalCourtsParser.loadConfig(), fixtures(), raw);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(DePepFederalCourtsParser.ID_PREFIX, DePepFederalCourtsParser.TOPIC);

        String message = DePepFederalCourtsParser.convert(outcomes, LocalDate.of(2026, 11, 4), 12, dataset);

        assertThat(message).contains("anciens présidents sortis depuis plus de 12 mois écartés");
        List<String> names = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema()))
                .map(e -> e.getValues("name").iterator().next()).toList();
        assertThat(names).contains("Stephan Harbarth", "Karin Angerer", "Bettina Limperg", "Inken Gallner", "Susanne Rublack")
                .doesNotContain("Klaus Tolksdorf", "Doris König", "Kay Scheller");
        assertThat(dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema()) && e.getValues("status").contains("ended")).count())
                .isEqualTo(1);
        assertThat(dataset.qualityReport().getTotalRejected()).isZero();
    }

    @Test
    void aBrokenPageIsReportedAndFailsWhenAllAreBroken(@TempDir Path raw) throws Exception {
        var config = DePepFederalCourtsParser.loadConfig();
        var base = fixtures();
        var outcomes = DePepFederalCourtsParser.readAll(config, url -> url.contains("bverwg") ? "<html></html>" : base.get(url), raw);
        assertThat(outcomes.stream().filter(o -> o.problem() != null).map(o -> o.court().key())).containsExactly("bverwg");

        assertThatThrownBy(() -> DePepFederalCourtsParser.readAll(config, url -> {
            throw new IOException("réseau coupé");
        }, raw)).isInstanceOf(IllegalStateException.class).hasMessageContaining("Aucune cour fédérale lisible");
    }
}
