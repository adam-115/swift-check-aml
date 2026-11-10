package com.swiftcheck.aml.plugins.bgpep.parliament;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * Réponses réelles de l'API de www.parliament.bg (8 novembre 2026), réduites à quelques députés : la présidente de l'Assemblée
 * (Dotsova), une députée réélue (Nazaryan, présidente de la 51e puis vice-présidente de la 52e législature), un député devenu
 * ministre et démissionnaire le 8 mai 2026 (Pulev), une députée partie en février 2026 (Ivanova), un député de la 51e législature
 * (Stoynev) et un député de la 52e (Stoev). Les fiches sont réduites à la naissance (pas d'adresse électronique).
 */
class BgPepParliamentParserTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 8);
    private static final String BASE = "https://www.parliament.bg/api/v1";

    @Test
    void readsAMandateWithItsGroupRegionAndRole() throws Exception {
        var mandates = BgPepParliamentParser.mandates(resource("ns62-current.json"));

        assertEquals(3, mandates.size());
        var chair = mandates.get(0);
        assertEquals("5237", chair.mpId());
        assertEquals("МИХАЕЛА", chair.first());
        assertEquals("ДОЦОВА", chair.last());
        assertEquals("ПГ \"Прогресивна България\"", chair.party());
        assertEquals("23-СОФИЯ", chair.region());
        assertEquals("Председател на НС", chair.role());
        assertEquals(LocalDate.of(2026, 4, 30), chair.start());
        assertEquals(null, chair.end(), "9999-12-31 = en cours");

        var resigned = BgPepParliamentParser.mandates(resource("ns62-boundary.json")).get(2);
        assertEquals(LocalDate.of(2026, 5, 8), resigned.end());
        assertEquals(null, resigned.role(), "« член » n'est pas une fonction à garder");
    }

    @Test
    void dropsContactDataFromProfiles() throws Exception {
        String full = "{\"A_ns_id\":62,\"A_ns_MP_BDate\":\"1984-03-17\",\"A_ns_B_City\":\"гр. Монтана\","
                + "\"A_ns_MP_Email\":\"x@parliament.bg\",\"A_ns_MP_phones\":\"+359 2 1\"}";
        String trimmed = BgPepParliamentParser.trimProfile("5237", full);

        assertTrue(trimmed.contains("1984-03-17"));
        assertFalse(trimmed.contains("@") || trimmed.contains("phones") || trimmed.contains("359"), trimmed);
    }

    @Test
    void probesWindowDaysThenPreviousAssembliesThenBoundaryDays(@TempDir Path rawDir) throws Exception {
        List<String> calls = new ArrayList<>();
        BgPepParliamentParser.download(url -> {
            calls.add(url);
            if (url.equals(BASE + "/coll-list-ns/bg")) {
                return resource("ns62-current.json");
            }
            if (url.contains("/mp-profile/bg/")) {
                return resource("profile-" + url.substring(url.lastIndexOf('/') + 1) + ".json");
            }
            if (url.contains("nsId=62&date=2026-05-07") || url.contains("nsId=62&date=2026-04-30")) {
                return resource("ns62-boundary.json");
            }
            if (url.contains("nsId=61&date=2025-11-08")) {
                return resource("ns61-early.json");
            }
            if (url.contains("nsId=61&date=2026-03-08")) {
                return resource("ns61-late.json");
            }
            return "{\"colListMP\":[]}";
        }, BASE + "/", TODAY, 12, 30, rawDir);

        assertTrue(calls.contains(BASE + "/coll-list-ns/bg?nsId=61&date=2025-11-08"), "début de la fenêtre, législature précédente");
        assertTrue(calls.contains(BASE + "/coll-list-ns/bg?nsId=62&date=2026-04-30"), "jour de passage entre les deux législatures");
        assertTrue(calls.stream().noneMatch(c -> c.contains("nsId=60")), "la 51e couvrait déjà le début de la fenêtre");
        assertTrue(Files.exists(rawDir.resolve("ns-62-2026-11-08.json")));
        assertTrue(Files.exists(rawDir.resolve("ns-61-2026-03-08.json")));
        assertFalse(Files.exists(rawDir.resolve("ns-61-2025-12-08.json")), "un jour sans composition n'est pas gardé");
        // une fiche par personne gardée, sans coordonnées
        assertTrue(Files.exists(rawDir.resolve("mp-5276.json")));
        assertEquals(2, calls.stream().filter(c -> c.endsWith("/mp-profile/bg/5237")).count(), "numéro de législature, puis naissance");
        String profile = Files.readString(rawDir.resolve("mp-4833.json"), StandardCharsets.UTF_8);
        assertTrue(profile.contains("1985-09-16"));
        assertFalse(profile.contains("@"));
    }

    @Test
    void writesOnePersonPerHumanWithAllHerMandates(@TempDir Path rawDir) throws Exception {
        copyFixtures(rawDir);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(BgPepParliamentParser.ID_PREFIX, BgPepParliamentParser.TOPIC);

        String message = new BgPepParliamentParser().convert(rawDir, TODAY, 12, dataset);

        assertTrue(message.startsWith("3 mandats en cours et 4 terminés"), message);
        Set<String> names = persons(dataset).stream().map(e -> e.getValues("name").iterator().next()).collect(Collectors.toSet());
        assertEquals(Set.of("Михаела Милчева Доцова", "Рая Назар Назарян", "Стою Теодоров Стоев", "Александър Георгиев Пулев",
                "Биляна Георгиева Иванова", "Драгомир Велков Стойнев"), names);

        FtmEntity nazaryan = person(dataset, "Рая Назар Назарян");
        assertEquals(Set.of("1985-09-16"), nazaryan.getValues("birthDate"));
        assertEquals(Set.of("Варна"), nazaryan.getValues("birthPlace"));
        assertTrue(nazaryan.getValues("alias").containsAll(Set.of("Рая Назарян", "Назарян Рая", "Raya Nazar Nazaryan", "Raya Nazaryan", "Nazaryan Raya")),
                nazaryan.getValues("alias").toString());
        assertEquals(Set.of("role.pep"), nazaryan.getValues("topics"));
        List<FtmEntity> stints = occupancies(dataset, nazaryan);
        assertEquals(2, stints.size(), "la 51e législature (terminée) et la 52e (en cours)");
        assertTrue(stints.stream().anyMatch(o -> o.getValues("status").contains("ended") && o.getValues("endDate").contains("2026-04-30")
                && o.getValues("description").stream().anyMatch(d -> d.contains("ГЕРБ") && d.contains("Председател на НС"))));
        assertTrue(stints.stream().anyMatch(o -> o.getValues("status").contains("current")));

        FtmEntity dotsova = person(dataset, "Михаела Милчева Доцова");
        assertEquals(Set.of("Монтана"), dotsova.getValues("birthPlace"), "« гр. » retiré");
        assertTrue(dotsova.getValues("alias").containsAll(Set.of("Mihaela Dotsova", "Dotsova Mihaela", "Mihaela Milcheva Dotsova")));

        FtmEntity pulev = person(dataset, "Александър Георгиев Пулев");
        assertEquals("2026-05-08", occupancies(dataset, pulev).get(0).getValues("endDate").iterator().next());
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertEquals(0, dataset.qualityReport().getIdConflictCount());
    }

    @Test
    void aShortWindowKeepsOnlyCurrentMandates(@TempDir Path rawDir) throws Exception {
        copyFixtures(rawDir);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(BgPepParliamentParser.ID_PREFIX, BgPepParliamentParser.TOPIC);

        String message = new BgPepParliamentParser().convert(rawDir, TODAY, 3, dataset);

        assertEquals(3, persons(dataset).size(), message);
        assertTrue(message.contains("(4 plus anciens écartés)") || message.contains("plus anciens écartés"), message);
    }

    @Test
    void transliteratesWithTheOfficialStreamlinedSystem() {
        assertEquals("Mihaela Dotsova", BgPepParliamentParser.transliterate("Михаела Доцова"));
        assertEquals("Maria Ivanova", BgPepParliamentParser.transliterate("Мария Иванова"));
        assertEquals("Shterev Zhivko", BgPepParliamentParser.transliterate("Щерев Живко"));
        assertEquals("Yordan Tsvetanov Hristov", BgPepParliamentParser.transliterate("Йордан Цветанов Христов"));
        assertEquals("Lachezar", BgPepParliamentParser.transliterate("Лъчезар"));
        assertEquals("Алиева-Вели", BgPepParliamentParser.titleCase("АЛИЕВА-ВЕЛИ"));
    }

    @Test
    void failsClearlyWithoutDataOrWithAnEmptyAssembly(@TempDir Path rawDir) {
        assertThrows(IllegalStateException.class, () -> new BgPepParliamentParser().convert(rawDir, TODAY, 12,
                new FtmDatasetBuilder("x", "role.pep")));
        assertThrows(IllegalStateException.class, () -> BgPepParliamentParser.download(url -> "{\"colListMP\":[]}", BASE, TODAY, 12, 30, rawDir));
        assertThrows(IllegalArgumentException.class, () -> BgPepParliamentParser.monthsAfterEnd(Map.of("monthsAfterEnd", "douze")));
    }

    @Test
    void probeDaysStartAtTheCutoffAndEndToday() {
        var days = BgPepParliamentParser.probeDays(LocalDate.of(2025, 11, 8), TODAY, 30);
        assertEquals(LocalDate.of(2025, 11, 8), days.get(0));
        assertEquals(TODAY, days.get(days.size() - 1));
        assertEquals(LocalDate.of(2025, 12, 8), days.get(1));
    }

    // ------------------------------------------------------------------ utilitaires

    private static void copyFixtures(Path rawDir) throws Exception {
        Files.writeString(rawDir.resolve("ns-62-2026-11-08.json"), resource("ns62-current.json"), StandardCharsets.UTF_8);
        Files.writeString(rawDir.resolve("ns-62-2026-04-30.json"), resource("ns62-boundary.json"), StandardCharsets.UTF_8);
        Files.writeString(rawDir.resolve("ns-61-2025-11-08.json"), resource("ns61-early.json"), StandardCharsets.UTF_8);
        Files.writeString(rawDir.resolve("ns-61-2026-03-08.json"), resource("ns61-late.json"), StandardCharsets.UTF_8);
        for (String id : List.of("5237", "5235", "5232", "5276", "4833", "4992", "4917")) {
            Files.writeString(rawDir.resolve("mp-" + id + ".json"),
                    BgPepParliamentParser.trimProfile(id, resource("profile-" + id + ".json")), StandardCharsets.UTF_8);
        }
    }

    private static List<FtmEntity> persons(FtmDatasetBuilder dataset) {
        return dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).toList();
    }

    private static FtmEntity person(FtmDatasetBuilder dataset, String name) {
        return persons(dataset).stream().filter(e -> e.getValues("name").contains(name)).findFirst().orElseThrow();
    }

    private static List<FtmEntity> occupancies(FtmDatasetBuilder dataset, FtmEntity person) {
        return dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema()) && e.getValues("holder").contains(person.getId())).toList();
    }

    private static String resource(String name) {
        try (InputStream in = BgPepParliamentParserTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
