package com.swiftcheck.aml.plugins.depep.landtage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/** Réponses réelles de l'API abgeordnetenwatch.de relevées le 4 novembre 2026 (Hessen 2024-2029, Rhénanie-Palatinat). */
class AbgeordnetenwatchLiveTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 4);

    private static String resource(String file) throws IOException {
        try (InputStream in = AbgeordnetenwatchLiveTest.class.getResourceAsStream("/" + file)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Serveur simulé : Hessen seulement, les autres Länder n'ont aucune législature. */
    private static AbgeordnetenwatchApi.Fetcher fake() {
        return url -> {
            if (url.contains("/parliaments?")) {
                return resource("aw-parliaments.json");
            }
            if (url.contains("parliament-periods?parliament=11&")) {
                return "{\"meta\":{\"status\":\"ok\"},\"data\":[{\"id\":150,\"label\":\"Hessen 2024 - 2029\",\"type\":\"legislature\","
                        + "\"start_date_period\":\"2024-01-18\",\"end_date_period\":\"2029-01-17\"}]}";
            }
            if (url.contains("parliament-periods?")) {
                return "{\"meta\":{\"status\":\"ok\"},\"data\":[]}";
            }
            if (url.contains("candidacies-mandates?") && url.contains("range_start=0")) {
                return resource("aw-mandates-he.json");
            }
            if (url.contains("candidacies-mandates?")) {
                return "{\"meta\":{\"status\":\"ok\",\"result\":{\"total\":133}},\"data\":[]}";
            }
            if (url.contains("/politicians/")) {
                return resource("aw-politician.json");
            }
            throw new IOException("URL inattendue : " + url);
        };
    }

    @Test
    void keepsTheCurrentLegislatureAndTheOneEndedLessThanAYearAgo() throws Exception {
        List<AbgeordnetenwatchApi.Period> periods = AbgeordnetenwatchApi.periods(resource("aw-periods-rp.json"));

        List<AbgeordnetenwatchApi.Period> active = AbgeordnetenwatchApi.activePeriods(periods, TODAY, 12);

        // 166 = 2026-2031 en cours ; 127 = 2021-2026 terminée le 18/05/2026 ; les « Wahl » et les anciennes sont écartées
        assertThat(active).extracting(AbgeordnetenwatchApi.Period::id).containsExactly(166L, 127L);
        assertThat(AbgeordnetenwatchApi.activePeriods(periods, TODAY, 3)).extracting(AbgeordnetenwatchApi.Period::id)
                .containsExactly(166L);
    }

    @Test
    void readsTheParliamentsAndTheirNamesFromTheApiOnly() throws Exception {
        List<AbgeordnetenwatchApi.Parliament> all = AbgeordnetenwatchApi.parliaments(resource("aw-parliaments.json"));

        assertThat(all).hasSize(18);
        assertThat(all).anyMatch(p -> p.label().equals("Hessen") && p.longLabel().startsWith("Landtag"));
        assertThat(DePepLandtageParser.slug("Nordrhein-Westfalen")).isEqualTo("nordrhein-westfalen");
        assertThat(DePepLandtageParser.slug("Thüringen")).isEqualTo("thuringen");
    }

    @Test
    void readsAMandateWithPartyConstituencyAndDates() throws Exception {
        AbgeordnetenwatchApi.Page page = AbgeordnetenwatchApi.mandatePage(resource("aw-mandates-he.json"));
        AbgeordnetenwatchApi.Period period = new AbgeordnetenwatchApi.Period(150, "Hessen 2024 - 2029", "legislature",
                LocalDate.of(2024, 1, 18), LocalDate.of(2029, 1, 17));

        AbgeordnetenwatchApi.Mandate m = page.items().stream()
                .map(n -> AbgeordnetenwatchApi.mandate(n, "hessen", period))
                .filter(x -> x.politicianId() == 185734L).findFirst().orElseThrow();

        assertThat(page.total()).isEqualTo(133);
        assertThat(m.name()).isEqualTo("Jürgen Harrer");
        assertThat(m.party()).isEqualTo("CDU");
        assertThat(m.constituency()).isEqualTo("46 - Offenbach Land III");
        assertThat(m.start()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(m.end()).isNull();
        assertThat(AbgeordnetenwatchApi.birthYear(resource("aw-politician.json"))).isEqualTo(1974);
    }

    @Test
    void readsAllPagesOfAllParliamentsAndKeepsTheRawFiles(@TempDir Path raw) throws Exception {
        DePepLandtageParser.LiveReport report = DePepLandtageParser.live(
                Map.of("birthYears", "true", "maxPoliticianCalls", "3"), raw, fake(), TODAY, 12);

        assertThat(report.mandates()).hasSize(100);
        assertThat(report.mandates()).allMatch(m -> m.parliamentKey().equals("hessen"));
        assertThat(report.lands()).hasSize(16); // Bundestag et Parlement européen exclus
        assertThat(report.lands().get("hessen").organization()).startsWith("Landtag");
        assertThat(report.birthYears()).hasSize(3); // plafond d'appels respecté
        assertThat(report.message()).contains("100 mandats lus sur 1 législatures");
        assertThat(Files.isRegularFile(raw.resolve("parliaments.json"))).isTrue();
        assertThat(Files.isRegularFile(raw.resolve("mandates-hessen-150-0.json"))).isTrue();
        try (var files = Files.list(raw)) {
            assertThat(files).allMatch(Files::isRegularFile);
        }
    }

    @Test
    void neverReusesAFileLeftByAPreviousRun(@TempDir Path raw) throws Exception {
        Files.writeString(raw.resolve("politicians.json"), "{\"185734\": 1900}");
        Files.writeString(raw.resolve("parliaments.json"), "{\"meta\":{\"status\":\"ok\"},\"data\":[]}");

        DePepLandtageParser.LiveReport report = DePepLandtageParser.live(
                Map.of("birthYears", "true", "maxPoliticianCalls", "500"), raw, fake(), TODAY, 12);

        assertThat(report.mandates()).hasSize(100); // la liste vide laissée dans rawDir n'a pas été utilisée
        assertThat(report.birthYears().get(185734L)).isEqualTo(1974); // lu à nouveau, pas 1900
    }

    @Test
    void failsWhenNothingIsRead(@TempDir Path raw) {
        AbgeordnetenwatchApi.Fetcher empty = url -> "{\"meta\":{\"status\":\"ok\"},\"data\":[]}";

        assertThatThrownBy(() -> DePepLandtageParser.live(Map.of(), raw, empty, TODAY, 12))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Aucun mandat");
    }

    @Test
    void appliesTheRetentionWindowAndWritesDatesAndBirthYear() throws Exception {
        LocalDate periodEnd = LocalDate.of(2026, 5, 18);
        AbgeordnetenwatchApi.Mandate current = new AbgeordnetenwatchApi.Mandate(1, 10, "Dr. Anna Beispiel", "https://x/a",
                "hessen", 150, "Hessen 2024 - 2029", LocalDate.of(2024, 1, 18), null, LocalDate.of(2029, 1, 17), "CDU", "46 - Offenbach");
        AbgeordnetenwatchApi.Mandate endedInWindow = new AbgeordnetenwatchApi.Mandate(2, 11, "Bernd Alt", null,
                "hessen", 127, "Hessen 2019 - 2024", LocalDate.of(2019, 1, 18), null, periodEnd, "SPD", null);
        AbgeordnetenwatchApi.Mandate tooOld = new AbgeordnetenwatchApi.Mandate(3, 12, "Carl Vieux", null,
                "hessen", 100, "Hessen 2014 - 2019", LocalDate.of(2014, 1, 18), LocalDate.of(2025, 1, 31), null, "FDP", null);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(DePepLandtageParser.ID_PREFIX, DePepLandtageParser.TOPIC);

        int written = DePepLandtageParser.convertMandates(List.of(current, endedInWindow, tooOld),
                Map.of("hessen", new DePepLandtageParser.Land("hessen", "Hessen", "Hessischer Landtag")), Map.of(10L, 1980),
                TODAY, 12, dataset);

        assertThat(written).isEqualTo(2);
        List<String> names = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema()))
                .map(e -> e.getValues("name").iterator().next()).toList();
        assertThat(names).containsExactlyInAnyOrder("Anna Beispiel", "Bernd Alt");
        FtmEntity anna = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())
                && e.getValues("name").contains("Anna Beispiel")).findFirst().orElseThrow();
        assertThat(anna.getValues("birthDate")).containsExactly("1980");
        assertThat(anna.getValues("title")).containsExactly("Dr.");
        FtmEntity ended = dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema())
                && e.getValues("endDate").contains("2026-05-18")).findFirst().orElseThrow();
        assertThat(ended.getValues("status")).containsExactly("ended");
        assertThat(dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema())
                && e.getValues("status").contains("current")).count()).isEqualTo(1);
    }
}
