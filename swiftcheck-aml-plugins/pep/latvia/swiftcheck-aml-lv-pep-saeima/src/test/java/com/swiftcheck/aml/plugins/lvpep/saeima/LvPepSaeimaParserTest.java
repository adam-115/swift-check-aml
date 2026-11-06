package com.swiftcheck.aml.plugins.lvpep.saeima;

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
 * Pages réelles de saeima.lv (6 novembre 2026) : trois députés de la 15e législature (dont une cheffe de groupe sans ligne
 * « Deputāts » dans l'activité) et cinq de la 14e (deux mandats déposés puis rétablis, un mandat déposé en 2023, un député réélu
 * dont le premier mandat a pris fin en 2025). Fiches coupées avant les commissions.
 */
class LvPepSaeimaParserTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 6);
    private static final List<String> FILES = List.of("list-15.html", "list-14.html",
            "deputy-15-15-liene-apine.html", "deputy-15-15-agnese-lace.html", "deputy-15-15-ainars-slesers.html",
            "deputy-14-14-hosams-abu-meri.html", "deputy-14-14-andris-spruds.html", "deputy-14-14-anda-caksa.html",
            "deputy-14-14-ainars-slesers.html", "deputy-14-14-atis-deksnis.html");

    @Test
    void listsEachDeputyOnceWithFactionAndDistrict() {
        var listed = LvPepSaeimaParser.listed(15, resource("list-15.html"));

        assertEquals(3, listed.size(), "la page présente chaque député deux fois (liste et galerie)");
        var apine = listed.get(0);
        assertEquals("Liene Apine", apine.name());
        assertEquals("APVIENOTAIS SARAKSTS", apine.faction());
        assertEquals("Rīga", apine.district());
        assertTrue(apine.current());
        assertEquals("deputy-15-15-liene-apine.html", apine.file());
    }

    @Test
    void readsMandatePeriodsFromTheHistory() {
        var apine = LvPepSaeimaParser.detail(resource("deputy-15-15-liene-apine.html"));
        assertEquals(List.of(new LvPepSaeimaParser.Stint(LocalDate.of(2026, 11, 3), null)), apine.stints());
        assertFalse(apine.deceased());

        var lace = LvPepSaeimaParser.detail(resource("deputy-15-15-agnese-lace.html"));
        assertEquals(List.of(new LvPepSaeimaParser.Stint(LocalDate.of(2026, 11, 3), null)), lace.stints(),
                "cheffe de groupe : pas de ligne « Deputāts », l'historique du mandat donne la date");

        var spruds = LvPepSaeimaParser.detail(resource("deputy-14-14-andris-spruds.html"));
        assertEquals(List.of(
                new LvPepSaeimaParser.Stint(LocalDate.of(2022, 11, 1), LocalDate.of(2023, 9, 20)),
                new LvPepSaeimaParser.Stint(LocalDate.of(2026, 5, 14), LocalDate.of(2026, 11, 3))), spruds.stints(),
                "mandat déposé (ministre) puis rétabli");
    }

    @Test
    void aDeathStatusMarksThePersonAsDeceased() {
        assertTrue(new LvPepSaeimaParser.Detail(List.of(), List.of("Izbeigušās Saeimas deputāta pilnvaras sakarā ar nāvi")).deceased());
    }

    @Test
    void downloadsTheListThenEveryDeputyOnce(@TempDir Path rawDir) throws Exception {
        List<String> calls = new ArrayList<>();
        LvPepSaeimaParser.download(url -> {
            calls.add(url);
            return url.endsWith("/15-saeima/deputati/") ? resource("list-15.html") : "<html/>";
        }, "https://www.saeima.lv/", List.of(15), rawDir);

        assertEquals(4, calls.size(), calls.toString());
        assertEquals("https://www.saeima.lv/lv/sasaukumi/15-saeima/deputati/", calls.get(0));
        assertTrue(Files.exists(rawDir.resolve("deputy-15-15-agnese-lace.html")));
    }

    @Test
    void standardWindowKeepsServingAndRecentMandatesOnly(@TempDir Path rawDir) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(LvPepSaeimaParser.ID_PREFIX, LvPepSaeimaParser.TOPIC);
        String message = run(rawDir, 12, dataset);

        assertEquals(Set.of("Liene Apine", "Agnese Lāce", "Ainārs Šlesers", "Andris Sprūds", "Anda Čakša"), names(dataset));
        assertTrue(message.startsWith("8 députés lus"), message);
        assertTrue(message.contains("5 personnes gardées"), message);
        FtmEntity apine = person(dataset, "Liene Apine");
        assertEquals(Set.of("role.pep"), apine.getValues("topics"));
        assertTrue(apine.getValues("position").contains("Député à la Saeima (Saeimas deputāts)"));
        assertEquals(1, occupancies(dataset, person(dataset, "Ainārs Šlesers")).size(), "premier mandat (fin 2025-06-10) hors fenêtre");
        var spruds = occupancies(dataset, person(dataset, "Andris Sprūds"));
        assertEquals(1, spruds.size(), "mandat de 2022-2023 hors fenêtre, mandat rétabli en 2026 gardé");
        assertTrue(spruds.get(0).getValues("startDate").contains("2026-05-14"));
        assertTrue(spruds.get(0).getValues("description").stream().anyMatch(d -> d.contains("14. Saeima")));
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertEquals(0, dataset.qualityReport().getIdConflictCount());
    }

    @Test
    void wideWindowKeepsEarlierMandatesAndMergesTheSamePerson(@TempDir Path rawDir) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(LvPepSaeimaParser.ID_PREFIX, LvPepSaeimaParser.TOPIC);
        run(rawDir, 600, dataset);

        Set<String> names = names(dataset);
        assertEquals(7, names.size(), names.toString());
        assertTrue(names.containsAll(Set.of("Hosams Abu Meri", "Atis Deksnis")), names.toString());
        assertEquals(2, occupancies(dataset, person(dataset, "Ainārs Šlesers")).size(), "une personne, deux législatures");
        assertEquals(2, occupancies(dataset, person(dataset, "Anda Čakša")).size());
        assertEquals(1, dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())
                && e.getValues("name").contains("Ainārs Šlesers")).count());
    }

    @Test
    void failsClearlyWithoutDownloadedFiles(@TempDir Path rawDir) {
        assertThrows(IllegalStateException.class, () -> new LvPepSaeimaParser().convert(rawDir, List.of(15), TODAY, 12,
                new FtmDatasetBuilder("x", "role.pep")));
    }

    @Test
    void rejectsInvalidParameters() {
        assertThrows(IllegalArgumentException.class, () -> LvPepSaeimaParser.compositions(Map.of("compositions", "15,x")));
        assertThrows(IllegalArgumentException.class, () -> LvPepSaeimaParser.monthsAfterEnd(Map.of("monthsAfterEnd", "douze")));
    }

    private static String run(Path rawDir, int months, FtmDatasetBuilder dataset) throws Exception {
        for (String f : FILES) {
            Files.writeString(rawDir.resolve(f), resource(f), StandardCharsets.UTF_8);
        }
        return new LvPepSaeimaParser().convert(rawDir, List.of(15, 14), TODAY, months, dataset);
    }

    private static Set<String> names(FtmDatasetBuilder dataset) {
        return dataset.entities().stream().filter(e -> "Person".equals(e.getSchema()))
                .map(e -> e.getValues("name").iterator().next()).collect(Collectors.toSet());
    }

    private static FtmEntity person(FtmDatasetBuilder dataset, String name) {
        return dataset.entities().stream().filter(e -> "Person".equals(e.getSchema()) && e.getValues("name").contains(name))
                .findFirst().orElseThrow();
    }

    private static List<FtmEntity> occupancies(FtmDatasetBuilder dataset, FtmEntity person) {
        return dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema())
                && e.getValues("holder").contains(person.getId())).toList();
    }

    private static String resource(String name) {
        try (InputStream in = LvPepSaeimaParserTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
