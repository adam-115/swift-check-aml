package com.swiftcheck.aml.plugins.atpep.parliament;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * Réponses réelles de l'interface JSON du Parlement autrichien (5 novembre 2026), réduites à quelques personnes :
 * Aldali (Nationalrat en cours), Hanel-Torsch (sortie en mars 2026), Pilz (sorti en 2017), Göll et Steiner-Wieser
 * (Bundesrat, sorties récentes), Stotter (Bundesrat en cours), Dienstleder (Bundesrat 1945, décédé).
 */
class AtPepParliamentParserTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 5);
    private static final Path FIXTURES = Path.of("src/test/resources/parliament");

    private static Path rawDir(Path dir) throws IOException {
        try (Stream<Path> files = Files.walk(FIXTURES)) {
            for (Path source : files.filter(Files::isRegularFile).toList()) {
                Path target = dir.resolve(FIXTURES.relativize(source));
                Files.createDirectories(target.getParent());
                Files.copy(source, target);
            }
        }
        return dir;
    }

    private static FtmDatasetBuilder dataset() {
        return new FtmDatasetBuilder(AtPepParliamentParser.ID_PREFIX, AtPepParliamentParser.TOPIC);
    }

    private static FtmEntity person(FtmDatasetBuilder dataset, String id) {
        return dataset.entities().stream().filter(e -> e.getId().equals(dataset.buildEntityId(id)))
                .findFirst().orElseThrow(() -> new AssertionError("personne absente : " + id));
    }

    private static List<FtmEntity> mandates(FtmDatasetBuilder dataset, FtmEntity holder) {
        return dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema())
                && e.getValues("holder").contains(holder.getId())).toList();
    }

    @Test
    void keepsCurrentMembersAndThoseWhoLeftLessThanAYearAgo(@TempDir Path dir) throws Exception {
        FtmDatasetBuilder dataset = dataset();

        AtPepParliamentParser.Report report = new AtPepParliamentParser().convert(rawDir(dir), TODAY, 12, dataset);

        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertEquals(2, report.current(), "Aldali (Nationalrat) et Stotter (Bundesrat)");
        assertEquals(3, report.former(), "Hanel-Torsch, Göll et Steiner-Wieser");
        assertEquals(0, report.deceased());

        FtmEntity aldali = person(dataset, "38385");
        assertEquals(Set.of("Lisa Aldali"), aldali.getValues("name"));
        assertEquals(Set.of("Lisa"), aldali.getValues("firstName"));
        assertEquals(Set.of("Aldali"), aldali.getValues("lastName"));
        assertEquals(Set.of("1989-09-13"), aldali.getValues("birthDate"));
        assertEquals(Set.of("Oberndorf bei Salzburg (Salzburg)"), aldali.getValues("birthPlace"));
        assertEquals(Set.of("role.pep"), aldali.getValues("topics"));
        FtmEntity mandate = mandates(dataset, aldali).get(0);
        assertEquals(Set.of("current"), mandate.getValues("status"));
        assertEquals(Set.of("2025-11-07"), mandate.getValues("startDate"));
        assertEquals(Set.of("NEOS Parlamentsklub"), mandate.getValues("politicalGroup"));
    }

    @Test
    void doesNotPublishAcademicTitlesInTheName(@TempDir Path dir) throws Exception {
        FtmDatasetBuilder dataset = dataset();
        new AtPepParliamentParser().convert(rawDir(dir), TODAY, 12, dataset);

        FtmEntity hanel = person(dataset, "30696");
        assertEquals(Set.of("Elke Hanel-Torsch"), hanel.getValues("name"));
        assertEquals(Set.of("Hanel-Torsch"), hanel.getValues("lastName"));
        assertEquals(Set.of("ended"), mandates(dataset, hanel).get(mandates(dataset, hanel).size() - 1).getValues("status"));
    }

    @Test
    void aMemberWhoLeftLongAgoIsKeptOnlyWithALongHistory(@TempDir Path dir) throws Exception {
        FtmDatasetBuilder shortHistory = dataset();
        new AtPepParliamentParser().convert(rawDir(dir.resolve("a")), TODAY, 12, shortHistory);
        assertTrue(shortHistory.entities().stream().noneMatch(e -> e.getId().equals(shortHistory.buildEntityId("1210"))),
                "Peter Pilz a quitté le Nationalrat en 2017");

        FtmDatasetBuilder longHistory = dataset();
        AtPepParliamentParser.Report report = new AtPepParliamentParser().convert(rawDir(dir.resolve("b")), TODAY, 240,
                longHistory);
        FtmEntity pilz = person(longHistory, "1210");
        assertEquals(Set.of("Peter Pilz"), pilz.getValues("name"));
        assertEquals(Set.of("1954-01-22"), pilz.getValues("birthDate"));
        assertTrue(report.former() >= 4);
    }

    @Test
    void aDeceasedMemberIsNeverPublished(@TempDir Path dir) throws Exception {
        FtmDatasetBuilder dataset = dataset();

        AtPepParliamentParser.Report report = new AtPepParliamentParser().convert(rawDir(dir), TODAY, 1200, dataset);

        assertEquals(1, report.deceased(), "Alois Dienstleder, Bundesrat 1945-1946, décédé en 1946");
        assertTrue(dataset.entities().stream().noneMatch(e -> e.getId().equals(dataset.buildEntityId("4"))));
    }

    @Test
    void readsBirthAndDeathFromThePersonRecord() throws Exception {
        ParliamentApi.Detail dead = ParliamentApi.parseDetail(
                Files.readString(FIXTURES.resolve("persons/4.json")));
        assertEquals("1885-06-17", dead.birthDate());
        assertEquals("1946-01-31", dead.deathDate());

        ParliamentApi.Detail alive = ParliamentApi.parseDetail(
                Files.readString(FIXTURES.resolve("persons/38385.json")));
        assertEquals(null, alive.deathDate());
        assertEquals("Lisa Aldali", alive.name());
    }

    @Test
    void splitsTheListNameUsingTheDisplayName() {
        assertEquals(List.of("Alexander", "Van der Bellen"),
                List.of(AtPepParliamentParser.splitName("Van der Bellen Alexander, Univ.-Prof. Dr.", "Alexander Van der Bellen")));
        assertEquals(List.of("Marlies", "Steiner-Wieser"),
                List.of(AtPepParliamentParser.splitName("Steiner-Wieser Marlies (bis 8.5.2025: Marlies Doppler)",
                        "Marlies Steiner-Wieser")));
        assertEquals("Lisa Aldali", AtPepParliamentParser.fallbackName("Aldali Lisa, Dr."));
    }

    @Test
    void downloadsThePersonRecordsOfKeptMembersOnly(@TempDir Path dir) throws Exception {
        Path raw = dir;
        for (String file : List.of("list-nationalrat.json", "list-bundesrat.json")) {
            Files.copy(FIXTURES.resolve(file), raw.resolve(file));
        }
        List<String> ids = AtPepParliamentParser.idsToDetail(raw, TODAY, 12);

        assertEquals(Set.of("38385", "30696", "23966", "3727", "22822"), Set.copyOf(ids));
    }

    @Test
    void refusesAnEmptyOrChangedList(@TempDir Path dir) throws Exception {
        ParliamentApi.Fetcher empty = new ParliamentApi.Fetcher() {
            @Override
            public String post(String url, String body) {
                return "{\"errorMsg\":\"<p>Fehler</p>\"}";
            }

            @Override
            public String get(String url) {
                return "{}";
            }
        };
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ParliamentApi.downloadLists(empty, AtPepParliamentParser.DEFAULT_LIST_URL, dir));
        assertTrue(e.getMessage().contains("Nationalrat"), e.getMessage());
    }
}
