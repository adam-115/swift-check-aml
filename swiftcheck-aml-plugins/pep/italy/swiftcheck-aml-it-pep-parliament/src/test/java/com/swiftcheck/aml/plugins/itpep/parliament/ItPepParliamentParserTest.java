package com.swiftcheck.aml.plugins.itpep.parliament;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.plugins.itpep.parliament.ItPepParliamentParser.Body;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * Réponses réelles des points SPARQL de la Chambre et du Sénat (5 novembre 2026), mandats en cours ou terminés depuis
 * moins de 12 mois. Umberto Bossi, mort le 19/03/2026, est dans les deux jeux : il ne doit jamais être publié.
 */
class ItPepParliamentParserTest {

    private static final Path FIXTURES = Path.of("src/test/resources/parliament");

    private static FtmDatasetBuilder dataset(Body body) {
        return new FtmDatasetBuilder(ItPepParliamentParser.ID_PREFIX + "-" + body.key, ItPepParliamentParser.TOPIC);
    }

    private static FtmEntity person(FtmDatasetBuilder dataset, String name) {
        return dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())
                && e.getValues("name").contains(name)).findFirst()
                .orElseThrow(() -> new AssertionError("personne absente : " + name));
    }

    private static List<FtmEntity> mandates(FtmDatasetBuilder dataset, FtmEntity holder) {
        return dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema())
                && e.getValues("holder").contains(holder.getId())).toList();
    }

    @Test
    void chamberKeepsCurrentAndRecentDeputiesButNotTheDeceased() throws Exception {
        FtmDatasetBuilder dataset = dataset(Body.CHAMBER);

        ItPepParliamentParser.Report report = new ItPepParliamentParser().convert(FIXTURES, Body.CHAMBER, dataset);

        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertEquals(1, report.deceased(), "Umberto Bossi");
        assertTrue(report.current() > 380, "environ 400 députés en fonction : " + report.current());
        assertTrue(report.former() >= 5, "députés sortis depuis moins de 12 mois : " + report.former());
        assertTrue(dataset.entities().stream().noneMatch(e -> e.getValues("name").contains("Umberto Bossi")));

        FtmEntity meloni = person(dataset, "Giorgia Meloni");
        assertEquals(Set.of("1977-01-15"), meloni.getValues("birthDate"));
        assertEquals(Set.of("Roma"), meloni.getValues("birthPlace"));
        assertEquals(Set.of("role.pep"), meloni.getValues("topics"));
        FtmEntity mandate = mandates(dataset, meloni).get(0);
        assertEquals(Set.of("current"), mandate.getValues("status"));
        assertEquals(Set.of("2022-09-30"), mandate.getValues("startDate"));
    }

    @Test
    void aDeputyWhoLeftIsPublishedAsEndedWithHisEndDate() throws Exception {
        FtmDatasetBuilder dataset = dataset(Body.CHAMBER);
        new ItPepParliamentParser().convert(FIXTURES, Body.CHAMBER, dataset);

        FtmEntity bitonci = person(dataset, "Massimo Bitonci");
        FtmEntity mandate = mandates(dataset, bitonci).get(0);
        assertEquals(Set.of("ended"), mandate.getValues("status"));
        assertEquals(Set.of("2025-12-28"), mandate.getValues("endDate"));
    }

    @Test
    void senateReadsBirthAndKeepsLifeAndCurrentSenators() throws Exception {
        FtmDatasetBuilder dataset = dataset(Body.SENATE);

        ItPepParliamentParser.Report report = new ItPepParliamentParser().convert(FIXTURES, Body.SENATE, dataset);

        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertEquals(1, report.deceased());
        assertTrue(report.current() > 200, "plus de 200 sénateurs en fonction : " + report.current());
        FtmEntity larussa = person(dataset, "Ignazio La Russa");
        assertEquals(Set.of("current"), mandates(dataset, larussa).get(0).getValues("status"));
        assertTrue(larussa.getValues("birthDate").iterator().next().startsWith("1947"), larussa.getValues("birthDate").toString());
    }

    @Test
    void governmentKeepsMinistersAndUndersecretariesWithTheirOwnDates() throws Exception {
        FtmDatasetBuilder dataset = dataset(Body.GOVERNMENT);

        ItPepParliamentParser.Report report = new ItPepParliamentParser().convert(FIXTURES, Body.GOVERNMENT, dataset);

        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertTrue(report.current() >= 60, "gouvernement en fonction : " + report.current());

        FtmEntity meloni = person(dataset, "Giorgia Meloni");
        List<FtmEntity> roles = mandates(dataset, meloni);
        assertEquals(2, roles.size(), "président du Conseil (une seule fois malgré deux libellés) et ministre du Tourisme par intérim");
        assertTrue(roles.stream().anyMatch(r -> r.getValues("status").contains("current")));
        assertTrue(roles.stream().anyMatch(r -> r.getValues("endDate").contains("2026-04-03")));
        assertTrue(dataset.entities().stream().anyMatch(e -> "Position".equals(e.getSchema())
                && e.getValues("topics").contains("gov.head")), "le président du Conseil est chef du gouvernement");

        FtmEntity mazzi = person(dataset, "Gianmarco Mazzi");
        assertTrue(mandates(dataset, mazzi).stream().anyMatch(m -> m.getValues("startDate").contains("2026-04-03")), "ministre depuis le 03/04/2026 après avoir été sous-secrétaire");
    }

    @Test
    void cleansTitlesAndNames() {
        assertEquals("Ministro del Turismo", ItPepParliamentParser.cleanTitle("Ministro del Turismo (26.03.2026-03.04.2026)"));
        assertEquals("Presidente del Consiglio dei Ministri",
                ItPepParliamentParser.cleanTitle("GIORGIA MELONI Presidente del Consiglio dei Ministri (21.10.2022)"));
        assertEquals("De Corato", ItPepParliamentParser.titleCase("DE CORATO"));
        assertEquals("D'Eramo", ItPepParliamentParser.titleCase("D'ERAMO"));
        assertEquals("San Benedetto Del Tronto",
                ItPepParliamentParser.birthPlace("nato a SAN BENEDETTO DEL TRONTO, ASCOLI PICENO, MARCHE, il 11.02.1965"));
        assertEquals("1965-02-11", ItPepParliamentParser.isoDate("19650211"));
    }

    @Test
    void pagesTheQueryAndStopsOnAShortPage(@TempDir Path dir) throws Exception {
        List<String> queries = new ArrayList<>();
        ParliamentSparql.Endpoint endpoint = (url, sparql) -> {
            queries.add(sparql);
            int rows = queries.size() < 3 ? 2 : 1;
            StringBuilder b = new StringBuilder("{\"results\":{\"bindings\":[");
            for (int i = 0; i < rows; i++) {
                b.append(i > 0 ? "," : "").append("{\"x\":{\"value\":\"").append(queries.size()).append("-").append(i).append("\"}}");
            }
            return b.append("]}}").toString();
        };

        int total = ParliamentSparql.download(endpoint, "http://x", "SELECT * WHERE {} ORDER BY ?x", "p", 2, 0, dir);

        assertEquals(5, total);
        assertTrue(queries.get(1).endsWith("LIMIT 2 OFFSET 2"), queries.get(1));
        assertEquals(5, ParliamentSparql.read(dir, "p").size());
        assertTrue(Files.exists(dir.resolve("p-002.json")));
    }

    @Test
    void refusesAnUnknownBodyAndAnEmptyAnswer(@TempDir Path dir) {
        assertThrows(IllegalArgumentException.class, () -> Body.of("regions"));
        assertEquals(Body.SENATE, Body.of("Senate"));
        assertThrows(IllegalStateException.class, () -> {
            Files.writeString(dir.resolve("chamber-000.json"), "{\"results\":{\"bindings\":[]}}");
            new ItPepParliamentParser().convert(dir, Body.CHAMBER, dataset(Body.CHAMBER));
        });
        assertThrows(IllegalArgumentException.class, () -> ItPepParliamentParser.monthsAfterEnd(Map.of("monthsAfterEnd", "abc")));
    }

    @SuppressWarnings("unused")
    private static LocalDate today() {
        return LocalDate.of(2026, 11, 5);
    }

    @SuppressWarnings("unused")
    private static String read(Path p) throws IOException {
        return Files.readString(p);
    }
}
