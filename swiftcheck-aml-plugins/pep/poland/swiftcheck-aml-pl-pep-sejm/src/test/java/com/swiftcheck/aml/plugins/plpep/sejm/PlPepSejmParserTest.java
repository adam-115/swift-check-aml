package com.swiftcheck.aml.plugins.plpep.sejm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * Réponses réelles de l'API de la Diète (6 novembre 2026) : les dix législatures, cinq députés de la dixième (dont un décédé,
 * un démissionnaire et le président du club KO), trois de la neuvième et le club KO.
 */
class PlPepSejmParserTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 6);
    private static final String API = "https://api.sejm.gov.pl/sejm";

    @Test
    void readsTermsAndMps() throws Exception {
        var terms = PlPepSejmParser.terms(resource("terms.json"));
        assertEquals(10, terms.size());
        assertNull(terms.get(9).to(), "dixième législature en cours");
        assertEquals(LocalDate.of(2023, 11, 12), terms.get(8).to());

        var mps = PlPepSejmParser.mps(resource("mp-10.json"));
        var andruszkiewicz = mps.stream().filter(m -> m.lastName().equals("Andruszkiewicz")).findFirst().orElseThrow();
        assertEquals("1990-06-30", andruszkiewicz.birthDate());
        assertEquals(LocalDate.of(2025, 8, 7), andruszkiewicz.expiry());
        assertEquals("Zrzeczenie", andruszkiewicz.inactiveCause());
        assertEquals("Adam Andruszkiewicz", andruszkiewicz.name());
    }

    @Test
    void downloadsOnlyTheTermsInTheWindow(@TempDir Path rawDir) throws Exception {
        List<String> calls = new ArrayList<>();
        PlPepSejmParser.download(url -> {
            calls.add(url);
            return url.endsWith("/term") ? resource("terms.json") : "[]";
        }, API + "/", TODAY, 12, rawDir);

        assertEquals(List.of(API + "/term", API + "/term10/MP", API + "/term10/clubs"), calls);

        List<String> wide = new ArrayList<>();
        PlPepSejmParser.download(url -> {
            wide.add(url);
            return url.endsWith("/term") ? resource("terms.json") : "[]";
        }, API, TODAY, 60, rawDir);
        assertTrue(wide.contains(API + "/term9/MP") && !wide.contains(API + "/term8/MP"), wide.toString());
    }

    @Test
    void parliamentKeepsServingMembersOnlyInTheStandardWindow(@TempDir Path rawDir) throws Exception {
        FtmDatasetBuilder dataset = run(rawDir, 12, "parliament");

        assertEquals(Set.of("Piotr Adamowicz", "Andrzej Adamczyk", "Zbigniew Konwiński"), names(dataset));
        FtmEntity andrzej = person(dataset, "Andrzej Adamczyk");
        assertEquals(Set.of("role.pep"), andrzej.getValues("topics"));
        assertEquals(Set.of("1959-01-04"), andrzej.getValues("birthDate"));
        assertTrue(andrzej.getValues("position").contains("Député à la Diète (poseł na Sejm)"));
        assertEquals(0, dataset.qualityReport().getTotalRejected());
        assertEquals(0, dataset.qualityReport().getIdConflictCount());
    }

    @Test
    void widerWindowAddsFormerMembersAndMergesTermsOfTheSamePerson(@TempDir Path rawDir) throws Exception {
        FtmDatasetBuilder dataset = run(rawDir, 60, "parliament");

        Set<String> names = names(dataset);
        assertTrue(names.containsAll(Set.of("Adam Andruszkiewicz", "Rafał Adamczyk", "Romuald Ajchler")), names.toString());
        assertTrue(!names.contains("Łukasz Litewka"), "décès enregistré écarté");
        long andrzejPersons = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())
                && e.getValues("name").contains("Andrzej Adamczyk")).count();
        assertEquals(1, andrzejPersons, "une seule personne pour deux législatures");
        long andrzejTerms = dataset.entities().stream().filter(e -> "Occupancy".equals(e.getSchema())
                && e.getValues("description").stream().anyMatch(d -> d.contains("Club : PiS"))
                && e.getValues("holder").stream().anyMatch(h -> h.equals(person(dataset, "Andrzej Adamczyk").getId()))).count();
        assertEquals(2, andrzejTerms);
        assertTrue(dataset.entities().stream().anyMatch(e -> "Occupancy".equals(e.getSchema())
                && e.getValues("endDate").contains("2025-08-07") && e.getValues("status").contains("ended")));
    }

    @Test
    void clubsLayerKeepsTheChairs(@TempDir Path rawDir) throws Exception {
        FtmDatasetBuilder dataset = run(rawDir, 12, "clubs");

        assertEquals(Set.of("Zbigniew Konwiński"), names(dataset));
        assertTrue(person(dataset, "Zbigniew Konwiński").getValues("position").stream().anyMatch(p -> p.contains("KO")));
    }

    @Test
    void rejectsAnUnknownScope() {
        assertThrows(IllegalArgumentException.class, () -> PlPepSejmParser.scope(Map.of("scope", "senate")));
    }

    @Test
    void failsClearlyWithoutDownloadedFiles(@TempDir Path rawDir) {
        assertThrows(IllegalStateException.class, () -> new PlPepSejmParser().convert(rawDir, TODAY, 12, "parliament",
                new FtmDatasetBuilder("x", "role.pep")));
    }

    private static FtmDatasetBuilder run(Path rawDir, int months, String scope) throws Exception {
        for (String f : List.of("terms.json", "mp-10.json", "mp-9.json", "clubs-10.json")) {
            Files.writeString(rawDir.resolve(f), resource(f), StandardCharsets.UTF_8);
        }
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(PlPepSejmParser.prefix(scope), PlPepSejmParser.TOPIC);
        new PlPepSejmParser().convert(rawDir, TODAY, months, scope, dataset);
        return dataset;
    }

    private static Set<String> names(FtmDatasetBuilder dataset) {
        return dataset.entities().stream().filter(e -> "Person".equals(e.getSchema()))
                .map(e -> e.getValues("name").iterator().next()).collect(Collectors.toSet());
    }

    private static FtmEntity person(FtmDatasetBuilder dataset, String name) {
        return dataset.entities().stream().filter(e -> "Person".equals(e.getSchema()) && e.getValues("name").contains(name))
                .findFirst().orElseThrow();
    }

    private static String resource(String name) {
        try (InputStream in = PlPepSejmParserTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
